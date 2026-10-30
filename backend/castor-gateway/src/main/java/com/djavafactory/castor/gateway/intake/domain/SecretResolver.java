package com.djavafactory.castor.gateway.intake.domain;

import java.util.Optional;

/** Finds the HMAC secret a source's {@code hmac_secret_ref} points to. The database holds the reference, never the secret. */
public interface SecretResolver {

    Optional<String> resolve(String reference);
}
