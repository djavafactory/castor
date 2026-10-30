package com.djavafactory.castor.gateway.intake.application;

import java.io.InputStream;

/**
 * A request to the intake endpoint as the api layer hands it over. The body is a stream because nothing may be read
 * before the key is checked.
 *
 * @param key the X-Castor-Key header, null when absent
 * @param timestamp the X-Castor-Timestamp header, null when absent
 * @param signature the X-Castor-Signature header, null when absent
 * @param declaredLength the Content-Length, -1 when unknown (chunked)
 */
public record IntakeRequest(
        String portId,
        String source,
        String key,
        String timestamp,
        String signature,
        long declaredLength,
        InputStream body) {}
