package com.djavafactory.castor.core.operations.domain;

import java.time.Instant;

import com.djavafactory.castor.contracts.Quality;

/** One canonical event behind a piece of state: what it was, when it happened, who said so and how. */
public record LineageEntry(String type, Instant occurredAt, String source, Quality quality) {}
