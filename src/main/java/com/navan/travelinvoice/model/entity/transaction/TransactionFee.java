package com.navan.travelinvoice.model.entity.transaction;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "transaction_fees")
public class TransactionFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected TransactionFee() {
    }

    public TransactionFee(String name, BigDecimal amount) {
        this.name = name;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }
}
