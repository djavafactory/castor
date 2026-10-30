package com.djavafactory.castor.gateway.admin.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * The service token of the internal admin API (CASTOR_SERVICE_TOKEN): a bearer token only castor-core knows. Without a
 * configured token nothing is accepted.
 */
@ApplicationScoped
public class ServiceToken {

    private static final String SCHEME = "Bearer ";

    private final byte[] expected;

    public ServiceToken(CastorConfig castor) {
        this.expected = castor.admin()
                .serviceToken()
                .filter(token -> !token.isBlank())
                .map(ServiceToken::digest)
                .orElse(null);
    }

    /** True when the {@code Authorization} header is {@code Bearer <the token>}. */
    public boolean accepts(String authorization) {
        return expected != null
                && authorization != null
                && authorization.startsWith(SCHEME)
                && MessageDigest.isEqual(digest(authorization.substring(SCHEME.length())), expected);
    }

    /** Both sides are hashed first, so the comparison takes the same time whatever the length of the token sent. */
    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
