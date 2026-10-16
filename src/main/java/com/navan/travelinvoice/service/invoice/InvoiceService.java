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
import java.util.HashMap;
import com.navan.travelinvoice.model.dto.common.PageResponse;
import com.navan.travelinvoice.model.dto.common.Pagination;
import org.springframework.transaction.annotation.Isolation;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
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
        InvoiceContents contents = copySources(lockTransactions(request.sortedTransactionIds()));
        Invoice invoice = new Invoice(contents.currency(), contents.grandTotal());
        contents.lines().forEach(invoice::addLine);
        return response(invoices.saveAndFlush(invoice));
    }

    @Transactional
    public InvoiceResponse patch(long id, CreateInvoiceRequest request) {
        Invoice invoice = lockInvoice(id);
        List<Long> requestedIds = request.sortedTransactionIds();
        TreeSet<Long> union = new TreeSet<>(requestedIds);
        invoice.getLines().forEach(line -> union.add(line.getTransactionId()));
        Map<Long, Transaction> locked = new TreeMap<>();
        lockTransactions(new ArrayList<>(union)).forEach(source -> locked.put(source.getId(), source));
        InvoiceContents contents = copySources(requestedIds.stream().map(locked::get).toList());
        invoice.clearLines();
        invoices.flush();
        invoice.updateTotals(contents.currency(), contents.grandTotal());
        contents.lines().forEach(invoice::addLine);
        return response(invoices.saveAndFlush(invoice));
    }

    @Transactional
    public void delete(long id) {
        Invoice invoice = lockInvoice(id);
        List<Long> ids = invoice.getLines().stream().map(InvoiceLineSnapshot::getTransactionId).sorted().toList();
        lockTransactions(ids);
        invoices.delete(invoice);
        invoices.flush();
    }

    private Invoice lockInvoice(long id) {
        return invoices.findByIdForUpdate(id).orElseThrow(() -> new InvoiceNotFoundException(id));
    }

    private List<Transaction> lockTransactions(List<Long> sortedIds) {
        List<Transaction> sources = new ArrayList<>();
        for (Long id : sortedIds) {
            sources.add(transactions.findByIdForUpdate(id)
                    .orElseThrow(() -> new TransactionNotFoundException(id)));
        }
        return sources;
    }

    private InvoiceContents copySources(List<Transaction> sources) {
        String currency = sources.getFirst().getCurrency();
        if (sources.stream().anyMatch(source -> !currency.equals(source.getCurrency()))) {
            throw new InvoiceConflictException("All invoice transactions must have the same currency");
        }
        BigDecimal grandTotal = sources.stream().map(Transaction::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grandTotal.abs().compareTo(MAX_TOTAL) > 0) {
            throw new InvalidInvoiceException("Invoice grand_total exceeds the supported monetary precision");
        }
        List<InvoiceLineSnapshot> lines = new ArrayList<>();
        for (Transaction source : sources) {
            List<TaxLine> taxes = source.getTaxLines().stream()
                    .map(tax -> new TaxLine(tax.getName(), tax.getRate(), tax.getAmount())).toList();
            List<FeeLine> fees = source.getFeeLines().stream()
                    .map(fee -> new FeeLine(fee.getName(), fee.getAmount())).toList();
            lines.add(new InvoiceLineSnapshot(source.getId(), source.getType(), source.getCurrency(),
                    source.getTotal(), source.getMetadata(), taxes, fees));
        }
        return new InvoiceContents(currency, grandTotal, lines);
    }

    private record InvoiceContents(String currency, BigDecimal grandTotal, List<InvoiceLineSnapshot> lines) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<InvoiceResponse> list(int limit, int offset) {
        Pagination.validate(limit, offset);
        List<Long> ids = invoices.findPageIds(limit, offset);
        if (ids.isEmpty()) return new PageResponse<>(List.of(), limit, offset);
        var parents = new HashMap<Long, Invoice>();
        invoices.fetchWithLines(ids).forEach(parent -> parents.put(parent.getId(), parent));
        // IN/fetch result ordering is deliberately ignored; the page ID query defines order.
        return new PageResponse<>(ids.stream().map(id -> response(parents.get(id))).toList(), limit, offset);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(long id) {
        return response(invoices.findWithLinesById(id).orElseThrow(() -> new InvoiceNotFoundException(id)));
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
