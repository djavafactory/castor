package com.djavafactory.castor.core.operations.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;

/** ops.reconciliation_item. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcReconciliationRepository implements ReconciliationRepository {

    private static final String SELECT = "select id, port_id, kind, entity_type, entity_id, event_id, detail, status, "
            + "resolved_by, note, created_at, resolved_at from ops.reconciliation_item ";

    private final OpsSql sql;

    JdbcReconciliationRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public ReconciliationItem open(ReconciliationItem i) {
        Object[] id = sql.queryOne(
                        "insert into ops.reconciliation_item (port_id, kind, entity_type, entity_id, event_id, detail, "
                                + "created_at) values (?, ?, ?, ?, ?, ?::jsonb, coalesce(?, now())) "
                                + "returning id, created_at",
                        r -> new Object[] {r.lng("id"), r.instant("created_at")},
                        i.portId(),
                        i.kind(),
                        i.entityType(),
                        i.entityId(),
                        i.eventId(),
                        i.detail() == null ? java.util.Map.of() : i.detail(),
                        i.createdAt())
                .orElseThrow();
        return new ReconciliationItem(
                (Long) id[0],
                i.portId(),
                i.kind(),
                i.entityType(),
                i.entityId(),
                i.eventId(),
                i.detail(),
                ReconciliationStatus.OPEN,
                null,
                null,
                (Instant) id[1],
                null);
    }

    @Override
    public Optional<ReconciliationItem> find(long id) {
        return sql.queryOne(SELECT + "where id = ?", JdbcReconciliationRepository::map, id);
    }

    @Override
    public List<ReconciliationItem> findByStatus(
            long portId, ReconciliationStatus status, ReconciliationKind kind, int limit) {
        return sql.query(
                SELECT + "where port_id = ? and status = ? and (?::text is null or kind = ?::text) "
                        + "order by created_at desc, id desc limit ?",
                JdbcReconciliationRepository::map,
                portId,
                status,
                kind == null ? null : kind.name(),
                kind == null ? null : kind.name(),
                limit);
    }

    @Override
    public boolean hasOpen(long portId, ReconciliationKind kind, String entityType, String entityId, String field) {
        return !sql.query(
                        "select 1 as one from ops.reconciliation_item where port_id = ? and kind = ? "
                                + "and entity_type = ? and entity_id = ? and status = 'OPEN' "
                                + "and detail ->> 'field' = ? limit 1",
                        r -> 1,
                        portId,
                        kind,
                        entityType,
                        entityId,
                        field)
                .isEmpty();
    }

    @Override
    public boolean resolve(long id, String resolvedBy, String note, Instant at) {
        return sql.update(
                        "update ops.reconciliation_item set status = 'RESOLVED', resolved_by = ?, note = ?, "
                                + "resolved_at = ? where id = ? and status = 'OPEN'",
                        resolvedBy,
                        note,
                        at,
                        id)
                == 1;
    }

    private static ReconciliationItem map(OpsSql.Row r) {
        return new ReconciliationItem(
                r.longOrNull("id"),
                r.lng("port_id"),
                r.enumOf(ReconciliationKind.class, "kind"),
                r.text("entity_type"),
                r.text("entity_id"),
                r.uuid("event_id"),
                r.jsonObject("detail"),
                r.enumOf(ReconciliationStatus.class, "status"),
                r.text("resolved_by"),
                r.text("note"),
                r.instant("created_at"),
                r.instant("resolved_at"));
    }
}
