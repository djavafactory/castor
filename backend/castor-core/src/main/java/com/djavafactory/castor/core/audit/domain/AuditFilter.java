package com.djavafactory.castor.core.audit.domain;

import java.time.Instant;

/** Which audit rows of a port to list. Every field except the limit is optional. */
public record AuditFilter(
        Instant from, Instant to, String actor, AuditAction action, String assetId, int limit, Long before) {}
