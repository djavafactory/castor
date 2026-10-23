package it.replica8.castor.core.shared.infrastructure;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/**
 * Runs the guards before anything touches the database: Flyway asks this bean for its configuration before it cleans
 * or migrates, and the start-up event repeats the check for runs without Flyway. Flyway clean is enabled only when
 * reset-on-start is true.
 */
@ApplicationScoped
public class ConfigGuardStartup implements FlywayConfigurationCustomizer {

    private final CastorConfig castor;

    public ConfigGuardStartup(CastorConfig castor) {
        this.castor = castor;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        ConfigGuard.verify(castor, ConfigProvider.getConfig());
        configuration.cleanDisabled(!castor.data().resetOnStart());
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        ConfigGuard.verify(castor, ConfigProvider.getConfig());
    }
}
