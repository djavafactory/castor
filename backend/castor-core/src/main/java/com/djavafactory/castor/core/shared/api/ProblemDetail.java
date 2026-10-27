package com.djavafactory.castor.core.shared.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** RFC 9457 Problem Details body, sent as application/problem+json. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String requestId,
        String errorId,
        List<Error> errors,
        String draftId) {

    public static final String MEDIA_TYPE = "application/problem+json";
    public static final String BASE_URI = "https://replica8.it/problems/";

    /** One failed rule or field. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String code, String assetId, String pointer, String message) {}
}
