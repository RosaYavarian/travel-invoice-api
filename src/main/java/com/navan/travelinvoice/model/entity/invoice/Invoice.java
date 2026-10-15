package com.navan.travelinvoice.model.entity.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "invoices")
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal grandTotal;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<InvoiceLineSnapshot> lines = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(String currency, BigDecimal grandTotal) {
        this.currency = currency;
        this.grandTotal = grandTotal;
    }

    public void addLine(InvoiceLineSnapshot line) {
        line.setInvoice(this);
        lines.add(line);
    }

    public Long getId() { return id; }
    public String getCurrency() { return currency; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public Instant getCreatedAt() { return createdAt; }
    public List<InvoiceLineSnapshot> getLines() { return Collections.unmodifiableList(lines); }
}
