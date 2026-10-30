package com.djavafactory.castor.gateway.admin.domain;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What an operator may read of a raw payload: every value under a key that names a key, secret, token, password,
 * signature or credential is replaced, and so is any text that looks like an API key or an authorization header, wherever
 * it is. The stored payload is never changed; only the copy that leaves the gateway is.
 */
public final class Redaction {

    public static final String MASK = "[REDACTED]";

    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i)(key|secret|token|passw|authorization|signature|credential|cookie|hmac)");
    private static final Pattern SECRET_TEXT =
            Pattern.compile("ck_[A-Za-z0-9_-]{16,}|(?i:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}");

    private Redaction() {}

    /** A copy of {@code node} without its secrets; null stays null. */
    public static JsonNode redact(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                boolean sensitive = SENSITIVE_KEY.matcher(field.getKey()).find()
                        && !field.getValue().isNull();
                copy.set(
                        field.getKey(), sensitive ? JsonNodeFactory.instance.textNode(MASK) : redact(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> copy.add(redact(item)));
            return copy;
        }
        if (node.isTextual()) {
            return JsonNodeFactory.instance.textNode(text(node.asText()));
        }
        return node;
    }

    /** The text with anything that looks like an API key or an authorization header masked. */
    public static String text(String text) {
        return text == null ? null : SECRET_TEXT.matcher(text).replaceAll(MASK);
    }
}
