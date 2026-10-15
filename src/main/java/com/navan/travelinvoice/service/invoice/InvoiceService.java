package com.navan.travelinvoice.service.invoice;

import com.navan.travelinvoice.exception.invoice.InvalidInvoiceException;
import com.navan.travelinvoice.exception.invoice.InvoiceConflictException;
import com.navan.travelinvoice.exception.invoice.InvoiceNotFoundException;
import com.navan.travelinvoice.exception.transaction.TransactionNotFoundException;
import com.navan.travelinvoice.model.dto.invoice.CreateInvoiceRequest;
import com.navan.travelinvoice.model.dto.invoice.InvoiceLineResponse;
import com.navan.travelinvoice.model.dto.invoice.InvoiceResponse;
import com.navan.travelinvoice.model.dto.transaction.FeeLine;
import com.navan.travelinvoice.model.dto.transaction.TaxLine;
import com.navan.travelinvoice.model.entity.invoice.Invoice;
import com.navan.travelinvoice.model.entity.invoice.InvoiceLineSnapshot;
import com.navan.travelinvoice.model.entity.transaction.Transaction;
import com.navan.travelinvoice.repository.invoice.InvoiceRepository;
import com.navan.travelinvoice.repository.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {
    private static final BigDecimal MAX_TOTAL = new BigDecimal("99999999999999999.99");
    private final InvoiceRepository invoices;
    private final TransactionRepository transactions;

    public InvoiceService(InvoiceRepository invoices, TransactionRepository transactions) {
        this.invoices = invoices;
        this.transactions = transactions;
    }

    @Transactional
    public InvoiceResponse create(CreateInvoiceRequest request) {
        List<Long> ids = request.sortedTransactionIds();
        List<Transaction> sources = new ArrayList<>();
        for (Long id : ids) {
            sources.add(transactions.findByIdForUpdate(id)
                    .orElseThrow(() -> new TransactionNotFoundException(id)));
        }
        String currency = sources.getFirst().getCurrency();
        if (sources.stream().anyMatch(source -> !currency.equals(source.getCurrency()))) {
            throw new InvoiceConflictException("All invoice transactions must have the same currency");
        }
        BigDecimal grandTotal = sources.stream().map(Transaction::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grandTotal.abs().compareTo(MAX_TOTAL) > 0) {
            throw new InvalidInvoiceException("Invoice grand_total exceeds the supported monetary precision");
        }
        Invoice invoice = new Invoice(currency, grandTotal);
        for (Transaction source : sources) {
            List<TaxLine> taxes = source.getTaxLines().stream()
                    .map(tax -> new TaxLine(tax.getName(), tax.getRate(), tax.getAmount())).toList();
            List<FeeLine> fees = source.getFeeLines().stream()
                    .map(fee -> new FeeLine(fee.getName(), fee.getAmount())).toList();
            invoice.addLine(new InvoiceLineSnapshot(source.getId(), source.getType(), source.getCurrency(),
                    source.getTotal(), source.getMetadata(), taxes, fees));
        }
        return response(invoices.saveAndFlush(invoice));
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(long id) {
        return response(invoices.findById(id).orElseThrow(() -> new InvoiceNotFoundException(id)));
    }

    private InvoiceResponse response(Invoice invoice) {
        List<InvoiceLineResponse> lines = invoice.getLines().stream().map(InvoiceLineResponse::from).toList();
        Map<TaxKey, BigDecimal> taxes = new TreeMap<>(Comparator.comparing(TaxKey::name)
                .thenComparing(TaxKey::rate, Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, BigDecimal> fees = new TreeMap<>();
        for (InvoiceLineResponse line : lines) {
            for (TaxLine tax : line.taxLines()) {
                BigDecimal rate = tax.rate() == null ? null : tax.rate().stripTrailingZeros();
                taxes.merge(new TaxKey(tax.name(), rate), tax.amount(), BigDecimal::add);
            }
            for (FeeLine fee : line.feeLines()) {
                fees.merge(fee.name(), fee.amount(), BigDecimal::add);
            }
        }
        List<TaxLine> taxRollup = taxes.entrySet().stream()
                .map(entry -> new TaxLine(entry.getKey().name(), entry.getKey().rate(), entry.getValue())).toList();
        List<FeeLine> feeRollup = fees.entrySet().stream()
                .map(entry -> new FeeLine(entry.getKey(), entry.getValue())).toList();
        return new InvoiceResponse(invoice.getId(), invoice.getCurrency(), invoice.getGrandTotal(),
                invoice.getCreatedAt(), lines, taxRollup, feeRollup);
    }

    private record TaxKey(String name, BigDecimal rate) {
    }
}
