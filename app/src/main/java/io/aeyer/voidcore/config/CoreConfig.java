package io.aeyer.voidcore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Cross-cutting beans that don't fit a domain package: clock, anything else
 * tiny enough that wiring a dedicated config class per bean would be more
 * ceremony than substance.
 */
@Configuration
public class CoreConfig {

    /**
     * UTC system clock. Injected anywhere code reasons about time so tests can
     * pin {@code Instant.now()} via {@link Clock#fixed} and exercise expiry
     * paths deterministically.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
