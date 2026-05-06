package io.aeyer.voidcore.monitoring;

import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a small set of Micrometer meters per SPEC §9.3:
 *
 *   ws.connections       (Gauge)   — count of open WS sessions
 *   ws.authenticated     (Gauge)   — count of authenticated sessions
 *   auth.login           (Counter) — total login attempts; tags: outcome
 *   auth.rate_limited    (Counter) — total rate-limit denials; tags: kind
 *   sysop.actions        (Counter) — total sysop tool mutations
 *
 * Counter beans are wired up here so the hot-path code that increments
 * them stays decoupled from the meter registry — call sites just call
 * {@code increment("outcome", "success")} or similar via
 * {@link VoidCoreMeters}.
 *
 * <p>v1 has no Prometheus scrape per SPEC §9.3 ("add later if usage
 * warrants"). The Micrometer MeterRegistry that Spring Boot Actuator
 * autoconfigures already exposes them at {@code /actuator/metrics}.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public VoidCoreMeters voidcoreMeters(MeterRegistry registry,
                                   SessionRegistry sessions,
                                   PresenceService presence) {
        // Gauges read live from the source of truth; no caching.
        registry.gauge("ws.connections", sessions, SessionRegistry::size);
        registry.gauge("ws.authenticated", presence, PresenceService::activeCount);

        return new VoidCoreMeters(
                Counter.builder("auth.login").tag("outcome", "success").register(registry),
                Counter.builder("auth.login").tag("outcome", "failure").register(registry),
                Counter.builder("auth.rate_limited").tag("kind", "login").register(registry),
                Counter.builder("auth.rate_limited").tag("kind", "registration").register(registry),
                Counter.builder("auth.rate_limited").tag("kind", "post").register(registry),
                Counter.builder("sysop.actions").register(registry));
    }
}
