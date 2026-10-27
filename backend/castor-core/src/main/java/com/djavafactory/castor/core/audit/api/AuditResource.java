package com.djavafactory.castor.core.audit.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.djavafactory.castor.core.audit.application.ListAudit;
import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.audit.domain.AuditEntry;
import com.djavafactory.castor.core.audit.domain.AuditFilter;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

/** Who changed what in the layouts of a port, and when. */
@Path("/api/v1/ports/{portCode}/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    public record Item(
            long id,
            Instant at,
            String actor,
            AuditAction action,
            String entityKind,
            String entityId,
            UUID layoutVersionId,
            JsonNode before,
            JsonNode after,
            String requestId) {}

    /** @param nextBefore pass as before to get the next page; null on the last page */
    public record Page(List<Item> items, Long nextBefore) {}

    private final ListAudit listAudit;
    private final ObjectMapper json;

    public AuditResource(ListAudit listAudit, ObjectMapper json) {
        this.listAudit = listAudit;
        this.json = json;
    }

    /** Newest first. Keyset pagination: before is the id of the last row of the previous page. */
    @GET
    @RolesAllowed("ADMIN")
    public Page list(
            @PathParam("portCode") String portCode,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("actor") String actor,
            @QueryParam("action") String action,
            @QueryParam("assetId") String assetId,
            @QueryParam("limit") Integer limit,
            @QueryParam("before") Long before) {
        int size = limit == null ? ListAudit.DEFAULT_LIMIT : limit;
        if (size < 1 || size > ListAudit.MAX_LIMIT) {
            throw new InvalidRequestException("limit must be between 1 and " + ListAudit.MAX_LIMIT);
        }
        var filter = new AuditFilter(
                instant("from", from), instant("to", to), blank(actor), action(action), blank(assetId), size, before);
        List<AuditEntry> rows = listAudit.execute(portCode, filter);
        List<Item> items = rows.stream().map(this::item).toList();
        Long next = rows.size() == size ? rows.get(rows.size() - 1).id() : null;
        return new Page(items, next);
    }

    private Item item(AuditEntry e) {
        return new Item(
                e.id(),
                e.at(),
                e.actor(),
                e.action(),
                e.entityKind(),
                e.entityId(),
                e.layoutVersionId(),
                tree(e.before()),
                tree(e.after()),
                e.requestId());
    }

    private JsonNode tree(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored audit snapshot is not JSON", e);
        }
    }

    private static Instant instant(String name, String value) {
        if (blank(value) == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(name + " must be an ISO-8601 instant such as 2026-01-31T00:00:00Z");
        }
    }

    private static AuditAction action(String value) {
        if (blank(value) == null) {
            return null;
        }
        try {
            return AuditAction.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown action '" + value + "'");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
