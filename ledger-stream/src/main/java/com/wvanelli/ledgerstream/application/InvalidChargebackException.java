package com.wvanelli.ledgerstream.application;

public class InvalidChargebackException extends RuntimeException {
    public InvalidChargebackException(String message) {
        super(message);
    }
}
