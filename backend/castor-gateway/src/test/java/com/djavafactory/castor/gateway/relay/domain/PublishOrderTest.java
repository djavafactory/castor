package com.djavafactory.castor.gateway.relay.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishOrderTest {

    private static OutboxMessage message(long id, String aggregate) {
        return new OutboxMessage(id, "event-" + id, aggregate, "equipment.status.changed", new byte[0]);
    }

    @Test
    void everythingAcknowledgedIsMarked() {
        var messages = List.of(message(1, "a"), message(2, "b"), message(3, "a"));

        assertEquals(List.of(1L, 2L, 3L), PublishOrder.markable(messages, List.of(true, true, true)));
    }

    @Test
    void nothingAcknowledgedMarksNothing() {
        var messages = List.of(message(1, "a"), message(2, "b"));

        assertEquals(List.of(), PublishOrder.markable(messages, List.of(false, false)));
    }

    @Test
    void afterAFailureNoLaterEventOfTheSameAggregateIsMarked() {
        var messages = List.of(message(1, "a"), message(2, "a"), message(3, "a"));

        // 2 failed, 3 was taken by the broker: 3 must be sent again after 2, so it stays unpublished
        assertEquals(List.of(1L), PublishOrder.markable(messages, List.of(true, false, true)));
    }

    @Test
    void otherAggregatesAreNotHeldBack() {
        var messages = List.of(message(1, "a"), message(2, "b"), message(3, "a"), message(4, "b"));

        assertEquals(List.of(2L, 4L), PublishOrder.markable(messages, List.of(false, true, true, true)));
    }

    @Test
    void anEmptyBatchMarksNothing() {
        assertEquals(List.of(), PublishOrder.markable(List.of(), List.of()));
    }

    @Test
    void mismatchedListsAreAProgrammingError() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PublishOrder.markable(List.of(message(1, "a")), List.of(true, true)));
    }
}
