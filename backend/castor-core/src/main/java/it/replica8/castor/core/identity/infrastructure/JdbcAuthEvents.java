package it.replica8.castor.core.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import io.quarkus.narayana.jta.QuarkusTransaction;

import it.replica8.castor.core.identity.application.AuthEvents;
import it.replica8.castor.core.identity.domain.AuthOutcome;

/** Writes security.auth_event with plain SQL (the inet column has no entity). Joins the running transaction. */
@ApplicationScoped
public class JdbcAuthEvents implements AuthEvents {

    private static final Pattern IP = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final EntityManager em;

    public JdbcAuthEvents(EntityManager em) {
        this.em = em;
    }

    @Override
    public void record(String username, AuthOutcome outcome, String remoteIp, String userAgent) {
        QuarkusTransaction.joiningExisting()
                .run(() -> em.createNativeQuery(
                                "insert into security.auth_event (username, outcome, remote_ip, "
                                        + "user_agent) values (:username, :outcome, cast(cast(:ip as varchar) as inet), :agent)")
                        .setParameter("username", truncate(username, 128))
                        .setParameter("outcome", outcome.name())
                        .setParameter("ip", cleanIp(remoteIp))
                        .setParameter("agent", truncate(userAgent, 300))
                        .executeUpdate());
    }

    /** The time of the last wrong password of this user, or null. Used to count failures within the window. */
    Instant lastFailure(String username) {
        Object value = em.createNativeQuery("select max(at) from security.auth_event "
                        + "where lower(username) = :username and outcome = 'FAILURE'")
                .setParameter("username", username.toLowerCase())
                .getSingleResult();
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }

    private static String cleanIp(String ip) {
        if (ip == null) {
            return null;
        }
        int zone = ip.indexOf('%');
        String bare = zone >= 0 ? ip.substring(0, zone) : ip;
        return IP.matcher(bare).matches() ? bare : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
