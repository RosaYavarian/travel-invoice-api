package com.navan.travelinvoice.repository.invoice;

import com.navan.travelinvoice.model.entity.invoice.InvoiceLineSnapshot;
import org.springframework.data.repository.Repository;

public interface InvoiceLineSnapshotRepository extends Repository<InvoiceLineSnapshot, Long> {
    boolean existsByTransactionId(Long transactionId);
}
