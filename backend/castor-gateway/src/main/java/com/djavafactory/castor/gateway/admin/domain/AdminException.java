package com.djavafactory.castor.gateway.admin.domain;

/**
 * An admin request core is not allowed to complete: the thing is not there, the state forbids it, or the input is wrong.
 * {@code code} is a stable machine word (for example {@code RAW_EXPIRED}); the message is for the operator.
 */
public final class AdminException extends RuntimeException {

    /** What kind of refusal it is; the API maps it to 404, 409 and 422. */
    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        INVALID
    }

    private final Kind kind;
    private final String code;

    private AdminException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static AdminException notFound(String code, String message) {
        return new AdminException(Kind.NOT_FOUND, code, message);
    }

    public static AdminException conflict(String code, String message) {
        return new AdminException(Kind.CONFLICT, code, message);
    }

    public static AdminException invalid(String code, String message) {
        return new AdminException(Kind.INVALID, code, message);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
