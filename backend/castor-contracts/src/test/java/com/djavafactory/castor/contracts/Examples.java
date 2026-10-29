package com.djavafactory.castor.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the example envelopes in src/test/resources/examples: one valid and one invalid per event type. */
final class Examples {

    private Examples() {}

    static String valid(EventType type) {
        return read(type, "valid");
    }

    static String invalid(EventType type) {
        return read(type, "invalid");
    }

    private static String read(EventType type, String kind) {
        String resource = "/examples/" + type.code() + "." + kind + ".json";
        try (InputStream in = Examples.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing example " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
