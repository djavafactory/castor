package com.djavafactory.castor.gateway.intake.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.djavafactory.castor.gateway.intake.domain.IntakeException.Kind;

/**
 * The optional signed webhook: {@code X-Castor-Signature} is the lower-case hex of
 * {@code HMAC-SHA256(secret, timestamp + "." + body)}, where {@code timestamp} is the text of
 * {@code X-Castor-Timestamp} (Unix seconds or an ISO-8601 instant), exactly as sent. A request is refused when its
 * timestamp is more than the allowed skew from the gateway clock, so a captured request cannot be replayed later.
 */
public final class RequestSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private RequestSignature() {}

    /** The signature a sender computes. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required by every Java runtime", e);
        }
    }

    /**
     * Applies the rules of a source: a signature is required when the source says so; when one is sent and the source
     * has a secret it must be valid, whether required or not.
     *
     * @param secret the secret of the source, empty when it has none or it is not configured
     * @throws IntakeException {@link Kind#UNAUTHORIZED} when the request must be refused
     */
    public static void check(
            boolean required,
            Optional<String> secret,
            String timestamp,
            String signature,
            byte[] body,
            Instant now,
            Duration maxSkew) {
        boolean hasTimestamp = timestamp != null && !timestamp.isBlank();
        boolean hasSignature = signature != null && !signature.isBlank();
        if (!hasTimestamp && !hasSignature) {
            if (required) {
                throw unauthorized("This source requires X-Castor-Timestamp and X-Castor-Signature");
            }
            return;
        }
        if (hasTimestamp != hasSignature) {
            throw unauthorized("Send X-Castor-Timestamp and X-Castor-Signature together");
        }
        if (secret.isEmpty()) {
            if (required) {
                throw unauthorized("The signature cannot be verified");
            }
            return;
        }
        Instant sent = parse(timestamp.trim());
        if (Duration.between(sent, now).abs().compareTo(maxSkew) > 0) {
            throw unauthorized("X-Castor-Timestamp is more than " + maxSkew.toMinutes() + " minutes from server time");
        }
        byte[] expected = sign(secret.get(), timestamp.trim(), body).getBytes(StandardCharsets.UTF_8);
        byte[] given = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, given)) {
            throw unauthorized("The signature does not match the request");
        }
    }

    private static Instant parse(String timestamp) {
        try {
            if (timestamp.chars().allMatch(Character::isDigit) && timestamp.length() <= 12) {
                return Instant.ofEpochSecond(Long.parseLong(timestamp));
            }
            return Instant.parse(timestamp);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw unauthorized("X-Castor-Timestamp must be Unix seconds or an ISO-8601 instant");
        }
    }

    private static IntakeException unauthorized(String detail) {
        return new IntakeException(Kind.UNAUTHORIZED, detail);
    }
}
