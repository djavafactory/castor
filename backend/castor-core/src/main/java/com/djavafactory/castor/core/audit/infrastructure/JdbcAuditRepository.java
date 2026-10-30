package com.djavafactory.castor.core.audit.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import com.djavafactory.castor.core.audit.domain.AuditAction;
import com.djavafactory.castor.core.audit.domain.AuditEntry;
import com.djavafactory.castor.core.audit.domain.AuditFilter;
import com.djavafactory.castor.core.audit.domain.AuditRepository;

/** audit.audit_log with native SQL (jsonb columns have no entity). Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcAuditRepository implements AuditRepository {

    private final EntityManager em;

    public JdbcAuditRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    public void append(
            String actor,
            AuditAction action,
            String entityKind,
            String entityId,
            UUID layoutVersionId,
            String before,
            String after,
            String requestId) {
        em.createNativeQuery("insert into audit.audit_log (port_id, actor, action, entity_kind, entity_id, "
                        + "layout_version_id, before, after, request_id) "
                        + "select (select port_id from layout.layout_version where id = :version), :actor, :action, "
                        + ":kind, :entity, :version, cast(cast(:before as varchar) as jsonb), "
                        + "cast(cast(:after as varchar) as jsonb), :request")
                .setParameter("version", layoutVersionId)
                .setParameter("actor", truncate(actor, 128))
                .setParameter("action", action.name())
                .setParameter("kind", truncate(entityKind, 64))
                .setParameter("entity", truncate(entityId, 128))
                .setParameter("before", before)
                .setParameter("after", after)
                .setParameter("request", truncate(requestId, 64))
                .executeUpdate();
    }

    @Override
    public void appendForPort(
            String portCode,
            String actor,
            AuditAction action,
            String entityKind,
            String entityId,
            String before,
            String after,
            String requestId) {
        int rows = em.createNativeQuery("insert into audit.audit_log (port_id, actor, action, entity_kind, entity_id, "
                        + "before, after, request_id) "
                        + "select p.id, :actor, :action, :kind, :entity, cast(cast(:before as varchar) as jsonb), "
                        + "cast(cast(:after as varchar) as jsonb), :request from ref.port p where p.code = :port")
                .setParameter("port", portCode)
                .setParameter("actor", truncate(actor, 128))
                .setParameter("action", action.name())
                .setParameter("kind", truncate(entityKind, 64))
                .setParameter("entity", truncate(entityId, 128))
                .setParameter("before", before)
                .setParameter("after", after)
                .setParameter("request", truncate(requestId, 64))
                .executeUpdate();
        if (rows != 1) {
            throw new IllegalStateException("No port " + portCode + " to audit a change of");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<AuditEntry> find(String portCode, AuditFilter f) {
        var sql = new StringBuilder("select a.id, a.at, a.actor, a.action, a.entity_kind, a.entity_id, "
                + "a.layout_version_id, cast(a.before as varchar), cast(a.after as varchar), a.request_id "
                + "from audit.audit_log a join ref.port p on p.id = a.port_id where p.code = :port");
        if (f.from() != null) {
            sql.append(" and a.at >= :from");
        }
        if (f.to() != null) {
            sql.append(" and a.at < :to");
        }
        if (f.actor() != null) {
            sql.append(" and lower(a.actor) = :actor");
        }
        if (f.action() != null) {
            sql.append(" and a.action = :action");
        }
        if (f.assetId() != null) {
            sql.append(" and a.entity_id = :asset");
        }
        if (f.before() != null) {
            sql.append(" and a.id < :before");
        }
        sql.append(" order by a.id desc limit :limit");
        var q = em.createNativeQuery(sql.toString()).setParameter("port", portCode);
        if (f.from() != null) {
            q.setParameter("from", f.from());
        }
        if (f.to() != null) {
            q.setParameter("to", f.to());
        }
        if (f.actor() != null) {
            q.setParameter("actor", f.actor().toLowerCase());
        }
        if (f.action() != null) {
            q.setParameter("action", f.action().name());
        }
        if (f.assetId() != null) {
            q.setParameter("asset", f.assetId());
        }
        if (f.before() != null) {
            q.setParameter("before", f.before());
        }
        q.setParameter("limit", f.limit());
        List<AuditEntry> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            out.add(new AuditEntry(
                    ((Number) r[0]).longValue(),
                    instant(r[1]),
                    (String) r[2],
                    AuditAction.valueOf((String) r[3]),
                    (String) r[4],
                    (String) r[5],
                    r[6] == null ? null : UUID.fromString(r[6].toString()),
                    (String) r[7],
                    (String) r[8],
                    (String) r[9]));
        }
        return out;
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
