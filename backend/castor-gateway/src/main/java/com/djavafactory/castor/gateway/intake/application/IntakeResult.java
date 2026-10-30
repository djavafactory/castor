package com.djavafactory.castor.gateway.intake.application;

import java.util.List;
import java.util.UUID;

import com.djavafactory.castor.gateway.intake.domain.IntakeException;

/** The answer to an accepted request: one record, or an array answered per item. */
public sealed interface IntakeResult {

    /**
     * One record's outcome.
     *
     * @param index position in the request array, 0 for a single record
     * @param status 202 stored, 200 repeat of a stored record, 422 a required field is missing or wrong, 400 the
     *     database cannot hold the JSON
     * @param receptionId set for 202 and 200
     */
    record Item(int index, int status, UUID receptionId, boolean duplicate, List<IntakeException.Field> errors) {}

    /** The body was one record: answered 202 {receptionId} or 200 {receptionId, duplicate: true}. */
    record Single(Item item) implements IntakeResult {}

    /** The body was an array: answered 207 with one {@link Item} per record. */
    record Batch(List<Item> items) implements IntakeResult {}
}
