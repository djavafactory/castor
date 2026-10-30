package com.djavafactory.castor.gateway.intake.domain;

import java.util.List;

/** A request the gateway does not accept, with the status and Problem Details slug the API answers it with. */
public final class IntakeException extends RuntimeException {

    /** The checks of the endpoint, in the order they run. */
    public enum Kind {
        /** 401: no key, a wrong key, a bad or missing signature. */
        UNAUTHORIZED(401, "unauthorized", "Authentication required"),
        /** 403: the key is good but the source is switched off. */
        FORBIDDEN(403, "forbidden", "Source disabled"),
        /** 413: more than 256 KB, or more than 500 records. */
        TOO_LARGE(413, "payload-too-large", "Payload too large"),
        /** 400: not JSON. */
        BAD_JSON(400, "bad-request", "Invalid request"),
        /** 422: JSON, but a record has no sourceEventId or occurredAt. */
        UNPROCESSABLE(422, "validation", "Invalid record");

        private final int status;
        private final String slug;
        private final String title;

        Kind(int status, String slug, String title) {
            this.status = status;
            this.slug = slug;
            this.title = title;
        }

        public int status() {
            return status;
        }

        public String slug() {
            return slug;
        }

        public String title() {
            return title;
        }
    }

    /**
     * One thing wrong with a record.
     *
     * @param pointer JSON Pointer to the field, empty for the whole record
     */
    public record Field(String code, String pointer, String message) {}

    private final Kind kind;
    private final List<Field> fields;

    public IntakeException(Kind kind, String detail) {
        this(kind, detail, List.of());
    }

    public IntakeException(Kind kind, String detail, List<Field> fields) {
        super(detail);
        this.kind = kind;
        this.fields = List.copyOf(fields);
    }

    public Kind kind() {
        return kind;
    }

    public List<Field> fields() {
        return fields;
    }
}
