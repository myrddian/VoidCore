package io.aeyer.voidcore.auth;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Rate-limiting thresholds per SPEC §5. Defaults match the spec; the env vars
 * are present for ops to tighten or loosen on the deploy box without a
 * rebuild.
 */
@ConfigurationProperties(prefix = "voidcore.rate-limit")
@Validated
public record RateLimitProperties(
        @Min(1) int loginFailuresPerWindow,
        @Min(1) int loginWindowMinutes,
        @Min(1) int loginLockoutMinutes,
        @Min(1) int registrationsPerHour,
        @Min(1) int postsPerMinute,
        @Min(1) int postBurstPerSecond
) {
    public static RateLimitProperties defaults() {
        return new RateLimitProperties(5, 15, 15, 3, 10, 1);
    }
}
