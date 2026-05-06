package io.aeyer.voidcore.auth;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Session lifetime configuration per SPEC §5. Sliding TTL — every successful
 * {@code auth.resume} extends {@code expires_at} by this duration from now.
 */
@ConfigurationProperties(prefix = "voidcore.session")
@Validated
public record SessionProperties(
        @Min(1) int ttlDays
) {
    public Duration ttl() {
        return Duration.ofDays(ttlDays);
    }
}
