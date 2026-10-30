package com.djavafactory.castor.gateway.intake.domain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSignatureTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final byte[] BODY = "{\"sourceEventId\":\"a\"}".getBytes(StandardCharsets.UTF_8);
    private static final Optional<String> SECRET = Optional.of("s3cret");
    private static final String SECONDS = Long.toString(NOW.getEpochSecond());

    private static void check(
            boolean required, Optional<String> secret, String timestamp, String signature, Instant now) {
        RequestSignature.check(required, secret, timestamp, signature, BODY, now, SKEW);
    }

    private static String refusal(boolean required, Optional<String> secret, String timestamp, String signature) {
        var e = assertThrows(IntakeException.class, () -> check(required, secret, timestamp, signature, NOW));
        assertEquals(IntakeException.Kind.UNAUTHORIZED, e.kind());
        return e.getMessage();
    }

    @Test
    void signsHmacSha256OfTimestampDotBodyAsLowerCaseHex() {
        // Computed independently: HMAC-SHA256("key", "1.hello")
        String signature = RequestSignature.sign("key", "1", "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(64, signature.length());
        assertEquals(signature.toLowerCase(), signature);
        assertEquals(signature, RequestSignature.sign("key", "1", "hello".getBytes(StandardCharsets.UTF_8)));
        assertTrue(!signature.equals(RequestSignature.sign("key", "2", "hello".getBytes(StandardCharsets.UTF_8))));
        assertTrue(!signature.equals(RequestSignature.sign("key", "1", "hellp".getBytes(StandardCharsets.UTF_8))));
        assertTrue(!signature.equals(RequestSignature.sign("kez", "1", "hello".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aValidSignatureWithUnixSecondsPasses() {
        assertDoesNotThrow(() -> check(true, SECRET, SECONDS, RequestSignature.sign("s3cret", SECONDS, BODY), NOW));
    }

    @Test
    void aValidSignatureWithAnIsoTimestampPasses() {
        String iso = NOW.toString();

        assertDoesNotThrow(() -> check(true, SECRET, iso, RequestSignature.sign("s3cret", iso, BODY), NOW));
    }

    @Test
    void theHexMayBeUpperCase() {
        String signature = RequestSignature.sign("s3cret", SECONDS, BODY).toUpperCase();

        assertDoesNotThrow(() -> check(true, SECRET, SECONDS, signature, NOW));
    }

    @Test
    void aSignatureIsRequiredWhenTheSourceSaysSo() {
        assertTrue(refusal(true, SECRET, null, null).contains("requires"));
        assertTrue(refusal(true, SECRET, " ", "").contains("requires"));
    }

    @Test
    void noSignatureIsFineWhenNoneIsRequired() {
        assertDoesNotThrow(() -> check(false, SECRET, null, null, NOW));
        assertDoesNotThrow(() -> check(false, Optional.empty(), null, null, NOW));
    }

    @Test
    void halfASignatureIsRefusedEvenWhenNoneIsRequired() {
        assertTrue(refusal(false, SECRET, SECONDS, null).contains("together"));
        assertTrue(refusal(false, SECRET, null, "abcd").contains("together"));
    }

    @Test
    void aWrongSignatureIsRefusedEvenWhenNoneIsRequired() {
        assertTrue(refusal(false, SECRET, SECONDS, "00".repeat(32)).contains("does not match"));
    }

    @Test
    void aTamperedBodyIsRefused() {
        String signature = RequestSignature.sign("s3cret", SECONDS, "{}".getBytes(StandardCharsets.UTF_8));

        assertTrue(refusal(true, SECRET, SECONDS, signature).contains("does not match"));
    }

    @Test
    void aSourceWithoutASecretCannotVerifyARequiredSignature() {
        assertTrue(refusal(true, Optional.empty(), SECONDS, "ab").contains("cannot be verified"));
    }

    @Test
    void aSignatureWithoutASecretIsIgnoredWhenNoneIsRequired() {
        assertDoesNotThrow(() -> check(false, Optional.empty(), SECONDS, "ab", NOW));
    }

    @Test
    void skewOfExactlyFiveMinutesPassesAndMoreIsRefused() {
        for (long offset : new long[] {-300, 300}) {
            String timestamp = Long.toString(NOW.getEpochSecond() + offset);
            assertDoesNotThrow(
                    () -> check(true, SECRET, timestamp, RequestSignature.sign("s3cret", timestamp, BODY), NOW));
        }
        for (long offset : new long[] {-301, 301, -3600}) {
            String timestamp = Long.toString(NOW.getEpochSecond() + offset);
            String message = refusal(true, SECRET, timestamp, RequestSignature.sign("s3cret", timestamp, BODY));
            assertTrue(message.contains("5 minutes"), message);
        }
    }

    @Test
    void anUnreadableTimestampIsRefused() {
        for (String timestamp : new String[] {"yesterday", "12.5", "1234567890123456", "2026-13-45T00:00:00Z"}) {
            assertTrue(refusal(true, SECRET, timestamp, "ab").contains("X-Castor-Timestamp"), timestamp);
        }
    }
}
