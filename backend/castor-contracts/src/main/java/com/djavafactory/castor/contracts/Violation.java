package com.djavafactory.castor.contracts;

/**
 * One reason an event is not a valid canonical event.
 *
 * @param path JSON Pointer to the offending value, empty for the whole event
 */
public record Violation(String path, String message) {}
