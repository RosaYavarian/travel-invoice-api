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
import java.util.List;
import java.util.HashMap;
import com.navan.travelinvoice.model.dto.common.PageResponse;
import com.navan.travelinvoice.model.dto.common.Pagination;
import org.springframework.transaction.TransactionDefinition;
import com.navan.travelinvoice.exception.transaction.TransactionConflictException;
import com.navan.travelinvoice.model.dto.transaction.PatchTransactionRequest;
import com.navan.travelinvoice.repository.invoice.InvoiceLineSnapshotRepository;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;
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
    private final TransactionTemplate listTransaction;
    private final TransactionTemplate singleReadTransaction;

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
        this.singleReadTransaction = new TransactionTemplate(transactionManager);
        this.singleReadTransaction.setReadOnly(true);
        this.singleReadTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.listTransaction = new TransactionTemplate(transactionManager);
        this.listTransaction.setReadOnly(true);
        this.listTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public TransactionResponse create(CreateTransactionRequest request) {
        validateBusinessFields(request);

        return writeTransaction.execute(status -> {
            var identity = repository.insertOrIdentify(request.externalId(), request.type().getValue(),
                    request.currency(), request.total(), request.occurredAt(), mapper.writeValueAsString(request.metadata()));
            if (!identity.getInserted()) {
                // Throw inside execute(): roll back the no-op conflict update before HTTP 409.
                throw new DuplicateTransactionException(identity.getId());
            }
            Transaction transaction = repository.findById(identity.getId()).orElseThrow();
            request.taxLines().forEach(tax -> transaction.addTaxLine(
                    new TransactionTax(tax.name(), tax.rate(), tax.amount())));
            request.feeLines().forEach(fee -> transaction.addFeeLine(
                    new TransactionFee(fee.name(), fee.amount())));
            repository.flush();
            return TransactionResponse.from(transaction);
        });
    }

    public PageResponse<TransactionResponse> list(int limit, int offset) {
        Pagination.validate(limit, offset);
        return listTransaction.execute(status -> {
            List<Long> ids = repository.findPageIds(limit, offset);
            if (ids.isEmpty()) return new PageResponse<>(List.of(), limit, offset);
            var parents = new HashMap<Long, Transaction>();
            repository.fetchWithTaxLines(ids).forEach(parent -> parents.put(parent.getId(), parent));
            // The same persistence context initializes fees on the already loaded parents.
            // Separate collection joins avoid both multiple-bag fetches and Cartesian products.
            repository.fetchWithFeeLines(ids);
            return new PageResponse<>(ids.stream().map(id -> TransactionResponse.from(parents.get(id))).toList(),
                    limit, offset);
        });
    }

    public TransactionResponse get(long id) {
        return singleReadTransaction.execute(status -> TransactionResponse.from(repository.findById(id)
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

}
