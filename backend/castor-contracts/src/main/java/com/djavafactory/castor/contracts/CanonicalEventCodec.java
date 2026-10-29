package com.djavafactory.castor.contracts;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON encoding of {@link CanonicalEvent}. Instants are ISO-8601 text, absent optional fields are left out, unknown
 * fields are ignored on decode (a v1 producer may add optional fields) and payload numbers keep their exact decimal
 * form, so encode then decode loses nothing.
 */
public final class CanonicalEventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public String encode(CanonicalEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new CanonicalEventException("Cannot encode event " + event.eventId(), e);
        }
    }

    public byte[] encodeBytes(CanonicalEvent event) {
        return encode(event).getBytes(StandardCharsets.UTF_8);
    }

    public CanonicalEvent decode(String json) {
        try {
            return MAPPER.readValue(json, CanonicalEvent.class);
        } catch (JsonProcessingException e) {
            throw new CanonicalEventException("Cannot decode event: " + e.getOriginalMessage(), e);
        }
    }

    public CanonicalEvent decode(byte[] json) {
        return decode(new String(json, StandardCharsets.UTF_8));
    }

    /** Parses event text into a tree with the same number handling as {@link #decode(String)}. */
    JsonNode readTree(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }
}
