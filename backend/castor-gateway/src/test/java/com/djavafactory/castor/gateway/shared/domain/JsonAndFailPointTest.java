package com.djavafactory.castor.gateway.shared.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAndFailPointTest {

    @Test
    void decimalsKeepTheirExactForm() throws Exception {
        JsonNode tree = Json.parse("{\"a\": 1.50, \"b\": 0.1000000000000000055511151231257827}".getBytes());

        assertEquals("{\"a\":1.50,\"b\":0.1000000000000000055511151231257827}", Json.write(tree));
    }

    @Test
    void textAfterTheFirstValueIsAnError() {
        assertThrows(JsonProcessingException.class, () -> Json.parse("{} {}".getBytes()));
        assertThrows(JsonProcessingException.class, () -> Json.parse("{\"a\":"));
    }

    @Test
    void emptyInputIsAMissingNode() throws Exception {
        assertTrue(Json.parse(new byte[0]).isMissingNode());
    }

    @Test
    void failPointsAreFoundByTheirCode() {
        for (FailPoint point : FailPoint.values()) {
            assertEquals(point, FailPoint.fromCode(point.code()).orElseThrow());
        }
        assertTrue(FailPoint.fromCode("nope").isEmpty());
    }

    @Test
    void failPointExceptionNamesItsPoint() {
        var e = new FailPoints.FailPointException(FailPoint.BEFORE_RELAY);

        assertEquals(FailPoint.BEFORE_RELAY, e.point());
        assertTrue(e.getMessage().contains("before-relay"));
    }
}
