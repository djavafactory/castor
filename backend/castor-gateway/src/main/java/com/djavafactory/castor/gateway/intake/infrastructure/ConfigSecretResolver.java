package com.djavafactory.castor.gateway.intake.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.intake.domain.SecretResolver;

/**
 * A reference such as {@code itap} is the configuration entry {@code gateway.hmac.itap}, which the environment variable
 * GATEWAY_HMAC_ITAP sets. Only that prefix is read, so a row in the database cannot point at any other setting.
 */
@ApplicationScoped
public class ConfigSecretResolver implements SecretResolver {

    private static final Logger LOG = Logger.getLogger(ConfigSecretResolver.class);
    private static final Pattern REFERENCE = Pattern.compile("[a-z0-9-]{1,40}");

    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            LOG.errorf("Not a valid HMAC secret reference: %s", reference);
            return Optional.empty();
        }
        Optional<String> secret =
                ConfigProvider.getConfig().getOptionalValue("gateway.hmac." + reference, String.class);
        if (secret.isEmpty()) {
            LOG.errorf("HMAC secret gateway.hmac.%s is not configured", reference);
        }
        return secret;
    }
}
