package com.djavafactory.castor.core.layout.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule catalogue against fixtures/validation-rules.json, the file the frontend tests read too: same codes, levels
 * and wording on both sides (DESIGNER-03 acceptance).
 */
class ValidationMessagesTest {

    private static final Path FILE = Path.of("..", "..", "fixtures", "validation-rules.json")
            .toAbsolutePath()
            .normalize();

    private static JsonNode fixture() throws IOException {
        return new ObjectMapper().readTree(FILE.toFile());
    }

    @Test
    void catalogueHasTheCodesLevelsAndWordingOfTheFixture() throws IOException {
        List<String> expected = StreamSupport.stream(fixture().get("rules").spliterator(), false)
                .map(r -> r.get("code").asText()
                        + "/" + r.get("variant").asText()
                        + "/" + r.get("level").asText()
                        + "/" + r.get("template").asText())
                .toList();

        List<String> actual = ValidationMessages.rules().stream()
                .map(r -> r.code() + "/" + r.variant() + "/" + r.level() + "/" + r.template())
                .toList();

        assertEquals(expected, actual);
    }

    @Test
    void everyExampleOfTheFixtureIsWordedTheSame() throws IOException {
        int checked = 0;
        for (JsonNode example : fixture().get("examples")) {
            Map<String, String> slots = new LinkedHashMap<>();
            example.get("params")
                    .fields()
                    .forEachRemaining(e -> slots.put(e.getKey(), e.getValue().asText()));

            String message = ValidationMessages.render(
                    example.get("code").asText(), example.get("variant").asText(), slots);

            assertEquals(
                    example.get("message").asText(),
                    message,
                    example.get("code").asText());
            checked++;
        }
        assertTrue(checked >= 18, "the fixture should show every code, found " + checked);
    }

    @Test
    void theReadmeCataloguesEighteenCodes() throws IOException {
        long codes = StreamSupport.stream(fixture().get("rules").spliterator(), false)
                .map(r -> r.get("code").asText())
                .distinct()
                .count();
        assertEquals(18, codes);
    }

    @Test
    void numbersAreWrittenWithoutTrailingZeros() {
        assertEquals("12", ValidationMessages.number(12.0));
        assertEquals("9.5", ValidationMessages.number(9.5));
        assertEquals("12.25", ValidationMessages.number(12.25));
        assertEquals("0.5", ValidationMessages.number(0.5));
    }

    @Test
    void everyVariantOfACodeHasTheSameLevel() {
        for (var rule : ValidationMessages.rules()) {
            assertEquals(rule.level(), ValidationMessages.level(rule.code()), rule.code());
        }
    }
}
