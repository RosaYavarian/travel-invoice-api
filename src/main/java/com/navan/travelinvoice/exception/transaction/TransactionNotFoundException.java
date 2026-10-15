package com.navan.travelinvoice.exception.transaction;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(long id) {
        super("Transaction " + id + " was not found");
    }
}
