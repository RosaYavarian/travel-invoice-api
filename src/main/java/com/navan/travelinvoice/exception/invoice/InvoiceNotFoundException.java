package com.navan.travelinvoice.exception.invoice;

public class InvoiceNotFoundException extends RuntimeException {
    public InvoiceNotFoundException(long id) {
        super("Invoice " + id + " was not found");
    }
}
