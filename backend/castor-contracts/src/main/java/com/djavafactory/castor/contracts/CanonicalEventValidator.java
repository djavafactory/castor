package com.djavafactory.castor.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

/**
 * Checks an event against the envelope schema and then, for a known type, the payload against the schema of that type
 * (JSON Schema draft 2020-12, formats asserted). It returns every violation found, not just the first. Schemas allow
 * unknown properties, because v1 may gain optional fields.
 */
public final class CanonicalEventValidator {

    private static final String ENVELOPE_SCHEMA = "/schemas/v1/envelope.json";

    private final CanonicalEventCodec codec = new CanonicalEventCodec();
    private final Schema envelope;
    private final Map<EventType, Schema> payloads = new EnumMap<>(EventType.class);

    public CanonicalEventValidator() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .formatAssertionsEnabled(true)
                        .build()));
        envelope = load(registry, ENVELOPE_SCHEMA);
        for (EventType type : EventType.values()) {
            payloads.put(type, load(registry, type.schemaResource()));
        }
    }

    /** Violations of an event given as JSON text; an empty list means the event is valid. */
    public List<Violation> validate(String json) {
        JsonNode tree;
        try {
            tree = codec.readTree(json);
        } catch (JsonProcessingException e) {
            return List.of(new Violation("", "Not valid JSON: " + e.getOriginalMessage()));
        }
        return validate(tree);
    }

    public List<Violation> validate(JsonNode event) {
        if (event == null || !event.isObject()) {
            return List.of(new Violation("", "An event must be a JSON object"));
        }
        List<Violation> violations = new ArrayList<>();
        envelope.validate(event).forEach(error -> violations.add(violation("", error)));

        JsonNode type = event.get("type");
        JsonNode payload = event.get("payload");
        if (type != null && type.isTextual()) {
            Optional<EventType> known = EventType.fromCode(type.asText());
            if (known.isEmpty()) {
                violations.add(
                        new Violation("/type", "Unknown event type '" + type.asText() + "' in schema version 1"));
            } else if (payload != null && payload.isObject()) {
                payloads.get(known.get())
                        .validate(payload)
                        .forEach(error -> violations.add(violation("/payload", error)));
            }
        }
        return List.copyOf(violations);
    }

    /** The library reports locations as "$.a[0].b"; events are reported as JSON Pointers ("/a/0/b"). */
    private static Violation violation(String prefix, Error error) {
        String location = error.getInstanceLocation().toString().replaceFirst("^\\$", "");
        String pointer = location.replace("[", "/").replace("]", "").replace('.', '/');
        return new Violation(prefix + pointer, error.getMessage());
    }

    private static Schema load(SchemaRegistry registry, String resource) {
        try (InputStream in = CanonicalEventValidator.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing schema " + resource);
            }
            return registry.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
