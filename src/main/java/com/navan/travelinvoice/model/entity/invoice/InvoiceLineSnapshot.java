package com.navan.travelinvoice.model.entity.invoice;

import com.navan.travelinvoice.model.dto.transaction.FeeLine;
import com.navan.travelinvoice.model.dto.transaction.TaxLine;
import com.navan.travelinvoice.model.entity.transaction.TransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "invoice_line_snapshots", uniqueConstraints = @UniqueConstraint(
        name = "uq_invoice_transaction", columnNames = {"invoice_id", "transaction_id"}))
public class InvoiceLineSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Convert(converter = TransactionType.DatabaseConverter.class)
    @Column(nullable = false, length = 30)
    private TransactionType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_lines", nullable = false, columnDefinition = "jsonb")
    private List<TaxLine> taxLines;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fee_lines", nullable = false, columnDefinition = "jsonb")
    private List<FeeLine> feeLines;

    protected InvoiceLineSnapshot() {
    }

    public InvoiceLineSnapshot(Long transactionId, TransactionType type, String currency,
                               BigDecimal total, JsonNode metadata, List<TaxLine> taxLines, List<FeeLine> feeLines) {
        this.transactionId = transactionId;
        this.type = type;
        this.currency = currency;
        this.total = total;
        this.metadata = metadata.deepCopy();
        this.taxLines = taxLines.stream().map(t -> new TaxLine(t.name(), t.rate(), t.amount())).toList();
        this.feeLines = feeLines.stream().map(f -> new FeeLine(f.name(), f.amount())).toList();
    }

    void setInvoice(Invoice invoice) { this.invoice = invoice; }
    public Long getTransactionId() { return transactionId; }
    public TransactionType getType() { return type; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotal() { return total; }
    public JsonNode getMetadata() { return metadata.deepCopy(); }
    public List<TaxLine> getTaxLines() { return List.copyOf(taxLines); }
    public List<FeeLine> getFeeLines() { return List.copyOf(feeLines); }
}
