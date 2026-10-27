package it.replica8.castor.core.shared.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reference data answers: ETag from the body, Cache-Control private for five minutes and 304 when the client already
 * holds the same body.
 */
@ApplicationScoped
public class CachedJson {

    public static final String CACHE_CONTROL = "private, max-age=300";

    private final ObjectMapper mapper;

    public CachedJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Serialises the body as JSON. */
    public Response of(String ifNoneMatch, Object body) {
        try {
            return raw(ifNoneMatch, mapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the response", e);
        }
    }

    /** Sends JSON text that is already serialised (GeoJSON built by the database). */
    public Response raw(String ifNoneMatch, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        EntityTag tag = new EntityTag(hash(bytes));
        if (matches(ifNoneMatch, tag.getValue())) {
            return Response.notModified(tag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }
        return Response.ok(bytes, MediaType.APPLICATION_JSON_TYPE)
                .tag(tag)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .build();
    }

    /** True when the If-None-Match header lists the tag (weak or strong) or is "*". */
    static boolean matches(String header, String value) {
        if (header == null || header.isBlank()) {
            return false;
        }
        for (String candidate : header.split(",")) {
            String c = candidate.trim();
            if (c.equals("*")) {
                return true;
            }
            if (c.startsWith("W/")) {
                c = c.substring(2);
            }
            if (c.equals("\"" + value + "\"")) {
                return true;
            }
        }
        return false;
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
