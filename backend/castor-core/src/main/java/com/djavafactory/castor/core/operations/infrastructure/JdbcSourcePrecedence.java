package com.djavafactory.castor.core.operations.infrastructure;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.SourcePrecedence;

/** ops.source_precedence; a field without a row takes the seeded default. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcSourcePrecedence implements SourcePrecedence {

    private final OpsSql sql;

    JdbcSourcePrecedence(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<List<String>> sources(long portId, String entityType, String field) {
        Optional<List<String>> stored = sql.queryOne(
                "select array_to_string(sources, ',') as sources from ops.source_precedence "
                        + "where port_id = ? and entity_type = ? and field = ?",
                r -> List.of(r.text("sources").toLowerCase().split(",")),
                portId,
                entityType,
                field);
        if (stored.isPresent() || !VESSEL_CALL.equals(entityType)) {
            return stored;
        }
        return Optional.ofNullable(VESSEL_CALL_DEFAULTS.get(field));
    }
}
