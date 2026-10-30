package com.djavafactory.castor.gateway.normalize.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A record taken from the inbox, as the source sent it.
 *
 * @param source the lower-case source code, which selects the {@link SourceAdapter}
 * @param raw the JSON of the record; decimals keep their exact form
 */
public record InboxRecord(
        UUID receptionId, String portId, String source, String sourceEventId, Instant receivedAt, JsonNode raw) {}
