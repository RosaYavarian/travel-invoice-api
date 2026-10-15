package com.navan.travelinvoice.exception.transaction;

public class DuplicateTransactionException extends RuntimeException {
    private final Long existingId;

    public DuplicateTransactionException(Long existingId) {
        super("A transaction with this external_id already exists");
        this.existingId = existingId;
    }

    public Long getExistingId() {
        return existingId;
    }
}
