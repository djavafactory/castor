package com.djavafactory.castor.gateway.intake.domain;

/**
 * A source of one port: whether it may send and how its requests are authenticated.
 *
 * @param portId the port code of the URL
 * @param requireSignature the request must carry a valid X-Castor-Signature
 * @param hmacSecretRef which configured secret signs requests of this source, null when none does
 */
public record IntegrationSource(
        long id, String portId, Source source, boolean enabled, boolean requireSignature, String hmacSecretRef) {}
