package com.navan.travelinvoice.repository.invoice;

import com.navan.travelinvoice.model.entity.invoice.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
}
