package com.matrix.payments.exception;

public class InvalidSignatureException extends RuntimeException {
    public InvalidSignatureException() {
        super("Missing or invalid callback signature");
    }
}
