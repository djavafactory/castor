package com.djavafactory.castor.core.operations.api;

/**
 * The outcome of a replay: APPLIED, ALREADY_APPLIED (the event had been applied after all) or FAILED. {@code attempt}
 * is the new row linked to the original.
 */
public record DeadLetterReplayDto(String outcome, DeadLetterDto attempt) {}
