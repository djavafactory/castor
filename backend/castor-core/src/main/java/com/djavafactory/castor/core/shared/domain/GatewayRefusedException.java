package com.djavafactory.castor.core.shared.domain;

/**
 * The gateway answered and said no: the thing is not there (404), the state forbids it (409) or the input is wrong
 * (400, 422). The status, the machine code and the message are the gateway's and go to the browser as they are.
 */
public final class GatewayRefusedException extends RuntimeException {

    private final int status;
    private final String code;

    public GatewayRefusedException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** The gateway's machine code (for example {@code RAW_EXPIRED}), null when it sent none. */
    public String code() {
        return code;
    }
}
