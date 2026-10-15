package com.navan.travelinvoice.model.entity.transaction;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, unique = true, length = 255)
    private String externalId;

    @Convert(converter = TransactionType.DatabaseConverter.class)
    @Column(name = "type", nullable = false, length = 30)
    private TransactionType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode metadata;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<TransactionTax> taxLines = new ArrayList<>();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<TransactionFee> feeLines = new ArrayList<>();

    protected Transaction() {
    }

    public Transaction(String externalId, TransactionType type, String currency, BigDecimal total, Instant occurredAt, JsonNode metadata) {
        this.externalId = externalId;
        this.type = type;
        this.currency = currency;
        this.total = total;
        this.occurredAt = occurredAt;
        this.metadata = metadata.deepCopy();
    }

    public Long getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public TransactionType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<TransactionTax> getTaxLines() {
        return Collections.unmodifiableList(taxLines);
    }

    public void addTaxLine(TransactionTax line) {
        line.setTransaction(this);
        taxLines.add(line);
    }

    public List<TransactionFee> getFeeLines() {
        return Collections.unmodifiableList(feeLines);
    }

    public void addFeeLine(TransactionFee line) {
        line.setTransaction(this);
        feeLines.add(line);
    }
}
