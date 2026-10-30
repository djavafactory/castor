package com.djavafactory.castor.core.operations.api;

import java.time.Instant;

import com.djavafactory.castor.contracts.Quality;

/** One canonical event behind a state or a timeline: what, when it happened, from which source and how. */
public record LineageEntryDto(String type, Instant occurredAt, String source, Quality quality) {}
