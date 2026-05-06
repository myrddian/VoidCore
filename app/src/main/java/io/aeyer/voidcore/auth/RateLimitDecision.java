package io.aeyer.voidcore.auth;

/**
 * Outcome of a rate-limit check. {@link Denied#retryAfterMs} maps directly
 * onto the {@code retry_after_ms} field of {@code error.RATE_LIMITED} per
 * SPEC §4.7.
 */
public sealed interface RateLimitDecision {

    record Allowed() implements RateLimitDecision {}

    record Denied(long retryAfterMs) implements RateLimitDecision {}

    RateLimitDecision ALLOWED = new Allowed();
}
