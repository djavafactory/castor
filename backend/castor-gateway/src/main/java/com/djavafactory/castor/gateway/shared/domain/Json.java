package com.djavafactory.castor.gateway.shared.domain;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

/**
 * JSON reading and writing with the number handling of {@code CanonicalEventCodec}: decimals keep their exact form, so
 * a payload that went through the inbox is the payload that was sent. Text after the first value is an error.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private Json() {}

    public static JsonNode parse(byte[] json) throws JsonProcessingException {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw e;
        } catch (IOException e) {
            // reading a byte array cannot fail for any other reason
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode parse(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write JSON", e);
        }
    }
}
