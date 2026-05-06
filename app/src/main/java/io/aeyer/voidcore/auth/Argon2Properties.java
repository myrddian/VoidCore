package io.aeyer.voidcore.auth;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Argon2id parameters per SPEC §5 and §9.1. Defaults are tuned to ~250ms
 * per hash on a typical homelab box; the {@code VOIDCORE_ARGON2_*} env vars
 * override on the deploy box if the calibration drifts (CPU swap, etc.).
 *
 * <p>Acceptance criterion (SPEC §13): Argon2 verify takes 200–400ms on the
 * deploy box. Tune {@code VOIDCORE_ARGON2_ITERATIONS} until this holds, then
 * leave it.
 */
@ConfigurationProperties(prefix = "voidcore.argon2")
@Validated
public record Argon2Properties(
        @Min(1024) int memoryKb,
        @Min(1) int iterations,
        @Min(1) int parallelism
) {
}
