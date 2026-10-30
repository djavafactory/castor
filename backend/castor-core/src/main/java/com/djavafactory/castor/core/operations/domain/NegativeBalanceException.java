package com.djavafactory.castor.core.operations.domain;

/** An adjustment would take a storage area below zero. The caller routes it to the reconciliation queue. */
public class NegativeBalanceException extends RuntimeException {

    public NegativeBalanceException(String message) {
        super(message);
    }
}
