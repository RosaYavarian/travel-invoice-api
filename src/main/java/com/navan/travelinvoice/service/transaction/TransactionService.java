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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {
    private final TransactionRepository repository;
    private final TransactionMetadataValidator metadataValidator;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public TransactionService(TransactionRepository repository,
                              TransactionMetadataValidator metadataValidator,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.metadataValidator = metadataValidator;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public TransactionResponse create(CreateTransactionRequest request) {
        try {
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException exception) {
            throw new InvalidTransactionException("currency must be a supported ISO currency code");
        }
        metadataValidator.validate(request.type(), request.metadata());

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
