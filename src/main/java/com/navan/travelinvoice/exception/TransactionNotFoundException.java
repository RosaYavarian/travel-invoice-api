package com.navan.travelinvoice.exception;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(long id) {
        super("Transaction " + id + " was not found");
    }
}
