package com.djavafactory.castor.core.shared.domain;

/**
 * The gateway cannot be asked: it is down, too slow, or it refused the service token (a configuration error, logged).
 * Reported as HTTP 502 without any detail of the gateway.
 */
public final class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public GatewayUnavailableException(String message) {
        super(message);
    }
}
