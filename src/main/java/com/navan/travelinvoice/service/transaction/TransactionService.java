package com.navan.travelinvoice.service.transaction;

import com.navan.travelinvoice.exception.transaction.DuplicateTransactionException;
import com.navan.travelinvoice.exception.transaction.InvalidTransactionException;
import com.navan.travelinvoice.exception.transaction.TransactionNotFoundException;
import com.navan.travelinvoice.model.dto.transaction.CreateTransactionRequest;
import com.navan.travelinvoice.model.dto.transaction.TransactionResponse;
import com.navan.travelinvoice.model.entity.transaction.Transaction;
import com.navan.travelinvoice.model.entity.transaction.TransactionFee;
import com.navan.travelinvoice.model.entity.transaction.TransactionTax;
import com.navan.travelinvoice.repository.transaction.TransactionRepository;
import java.util.Currency;
import com.navan.travelinvoice.exception.transaction.TransactionConflictException;
import com.navan.travelinvoice.model.dto.transaction.PatchTransactionRequest;
import com.navan.travelinvoice.repository.invoice.InvoiceLineSnapshotRepository;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {
    private final TransactionRepository repository;
    private final InvoiceLineSnapshotRepository snapshots;
    private final Validator validator;
    private final JsonMapper mapper;
    private final TransactionMetadataValidator metadataValidator;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public TransactionService(TransactionRepository repository,
                              TransactionMetadataValidator metadataValidator,
                              PlatformTransactionManager transactionManager,
                              InvoiceLineSnapshotRepository snapshots, Validator validator, JsonMapper mapper) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.validator = validator;
        this.mapper = mapper;
        this.metadataValidator = metadataValidator;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public TransactionResponse create(CreateTransactionRequest request) {
        validateBusinessFields(request);

        try {
            return writeTransaction.execute(status -> {
                Transaction transaction = new Transaction(request.externalId(), request.type(),
                        request.currency(), request.total(), request.occurredAt(), request.metadata());
                request.taxLines().forEach(tax -> transaction.addTaxLine(
                        new TransactionTax(tax.name(), tax.rate(), tax.amount())));
                request.feeLines().forEach(fee -> transaction.addFeeLine(
                        new TransactionFee(fee.name(), fee.amount())));
                return TransactionResponse.from(repository.saveAndFlush(transaction));
            });
        } catch (DataIntegrityViolationException exception) {
            if (!isExternalIdConflict(exception)) {
                throw exception;
            }
            Long existingId = readTransaction.execute(status -> repository.findByExternalId(request.externalId())
                    .map(Transaction::getId).orElse(null));
            if (existingId == null) {
                throw exception;
            }
            throw new DuplicateTransactionException(existingId);
        }
    }

    public TransactionResponse get(long id) {
        return readTransaction.execute(status -> TransactionResponse.from(repository.findById(id)
                .orElseThrow(() -> new TransactionNotFoundException(id))));
    }

    public TransactionResponse patch(long id, PatchTransactionRequest patch) {
        return writeTransaction.execute(status -> {
            Transaction transaction = lockUninvoiced(id);
            TransactionResponse current = TransactionResponse.from(transaction);
            CreateTransactionRequest updated = patch.applyTo(new CreateTransactionRequest(current.type(),
                    current.externalId(), current.currency(), current.occurredAt(), current.total(),
                    current.taxLines(), current.feeLines(), current.metadata()), mapper);
            var violations = validator.validate(updated);
            if (!violations.isEmpty()) {
                throw new InvalidTransactionException(violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                        .collect(java.util.stream.Collectors.joining("; ")));
            }
            validateBusinessFields(updated);
            if (patch.has("total")) transaction.updateTotal(updated.total());
            if (patch.has("occurred_at")) transaction.updateOccurredAt(updated.occurredAt());
            if (patch.has("metadata")) transaction.replaceMetadata(updated.metadata());
            if (patch.has("tax_lines")) transaction.replaceTaxLines(updated.taxLines().stream()
                    .map(tax -> new TransactionTax(tax.name(), tax.rate(), tax.amount())).toList());
            if (patch.has("fee_lines")) transaction.replaceFeeLines(updated.feeLines().stream()
                    .map(fee -> new TransactionFee(fee.name(), fee.amount())).toList());
            repository.flush();
            return TransactionResponse.from(transaction);
        });
    }

    public void delete(long id) {
        writeTransaction.executeWithoutResult(status -> {
            Transaction transaction = lockUninvoiced(id);
            repository.delete(transaction);
            repository.flush();
        });
    }

    private Transaction lockUninvoiced(long id) {
        Transaction transaction = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new TransactionNotFoundException(id));
        if (snapshots.existsByTransactionId(id)) {
            throw new TransactionConflictException(id);
        }
        return transaction;
    }

    private void validateBusinessFields(CreateTransactionRequest request) {
        try {
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException exception) {
            throw new InvalidTransactionException("currency must be a supported ISO currency code");
        }
        metadataValidator.validate(request.type(), request.metadata());
    }

    private boolean isExternalIdConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "transactions_external_id_key".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
