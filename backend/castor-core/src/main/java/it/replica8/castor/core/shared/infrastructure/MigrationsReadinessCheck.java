package it.replica8.castor.core.shared.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

/** Ready only when the database answers and no migration is pending. The datasource check covers connectivity alone. */
@Readiness
@ApplicationScoped
public class MigrationsReadinessCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(MigrationsReadinessCheck.class);

    private final Flyway flyway;

    public MigrationsReadinessCheck(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public HealthCheckResponse call() {
        var builder = HealthCheckResponse.named("Database migrations");
        try {
            int pending = flyway.info().pending().length;
            return (pending == 0 ? builder.up() : builder.down())
                    .withData("pending", pending)
                    .build();
        } catch (RuntimeException e) {
            LOG.debug("Migration status unavailable", e);
            return builder.down().withData("reason", "database not reachable").build();
        }
    }
}
