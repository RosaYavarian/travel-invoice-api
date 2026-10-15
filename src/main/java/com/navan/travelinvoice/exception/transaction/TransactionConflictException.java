package com.navan.travelinvoice.exception.transaction;

public class TransactionConflictException extends RuntimeException {
    public TransactionConflictException(long id) {
        super("Transaction " + id + " belongs to an invoice and cannot be modified or deleted");
    }
}
