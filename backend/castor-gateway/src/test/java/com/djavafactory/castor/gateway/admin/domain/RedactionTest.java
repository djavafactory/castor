package com.djavafactory.castor.gateway.admin.domain;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.shared.domain.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedactionTest {

    private static JsonNode json(String text) throws Exception {
        return Json.parse(text);
    }

    @Test
    void valuesUnderSensitiveKeysAreMaskedAtAnyDepthAndInArrays() throws Exception {
        JsonNode redacted = Redaction.redact(json("""
                {"apiKey": "a", "X-Castor-Key": "b", "client_secret": "c", "token": 1, "Password": "d",
                 "signature": "e", "authorization": "f", "ok": "visible", "n": 12.50,
                 "deep": {"hmacSecret": "g", "list": [{"credentials": {"u": "x"}}, "plain"]}}"""));

        assertEquals("[REDACTED]", redacted.get("apiKey").asText());
        assertEquals("[REDACTED]", redacted.get("X-Castor-Key").asText());
        assertEquals("[REDACTED]", redacted.get("client_secret").asText());
        assertEquals("[REDACTED]", redacted.get("token").asText());
        assertEquals("[REDACTED]", redacted.get("Password").asText());
        assertEquals("[REDACTED]", redacted.get("signature").asText());
        assertEquals("[REDACTED]", redacted.get("authorization").asText());
        assertEquals("[REDACTED]", redacted.get("deep").get("hmacSecret").asText());
        assertEquals(
                "[REDACTED]",
                redacted.get("deep").get("list").get(0).get("credentials").asText());
        assertEquals("plain", redacted.get("deep").get("list").get(1).asText());
        assertEquals("visible", redacted.get("ok").asText());
        assertEquals("12.50", redacted.get("n").decimalValue().toPlainString(), "numbers keep their exact form");
    }

    @Test
    void textThatLooksLikeAKeyOrAnAuthorizationHeaderIsMaskedWhateverItsKey() throws Exception {
        JsonNode redacted = Redaction.redact(json("""
                {"note": "key ck_0123456789abcdefABCDEF-_ used", "h": "Bearer abcdefgh12345678", "short": "ck_abc",
                 "text": "basic dXNlcjpwYXNzd29yZA==", "list": ["x ck_0123456789abcdef01 y"]}"""));

        assertEquals("key [REDACTED] used", redacted.get("note").asText());
        assertEquals("[REDACTED]", redacted.get("h").asText());
        assertEquals("ck_abc", redacted.get("short").asText(), "too short to be a key");
        assertEquals("[REDACTED]", redacted.get("text").asText());
        assertEquals("x [REDACTED] y", redacted.get("list").get(0).asText());
    }

    @Test
    void aNullValueUnderASensitiveKeyStaysNullAndNullStaysNull() throws Exception {
        JsonNode redacted = Redaction.redact(json("{\"token\": null}"));

        assertEquals(true, redacted.get("token").isNull());
        assertNull(Redaction.redact(null));
        assertNull(Redaction.text(null));
    }

    @Test
    void theInputIsNotChanged() throws Exception {
        JsonNode original = json("{\"password\": \"hunter2\", \"a\": [\"ck_0123456789abcdef0123\"]}");

        Redaction.redact(original);

        assertEquals("hunter2", original.get("password").asText());
        assertEquals("ck_0123456789abcdef0123", original.get("a").get(0).asText());
    }
}
