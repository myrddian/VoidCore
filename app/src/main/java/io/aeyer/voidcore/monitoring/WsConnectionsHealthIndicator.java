package io.aeyer.voidcore.monitoring;

import io.aeyer.voidcore.ws.SessionRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Adds an "ws" component to {@code /actuator/health} reporting the live
 * count of authenticated WebSocket sessions per SPEC §9.3 ("DB connectivity
 * + WS connection count via a custom HealthIndicator").
 *
 * <p>Always reports UP — there's no error condition for "no current users";
 * a quiet board is normal at 03:00 local. The count is what matters.
 */
@Component("wsConnections")
public class WsConnectionsHealthIndicator implements HealthIndicator {

    private final SessionRegistry registry;

    public WsConnectionsHealthIndicator(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("connections", registry.size())
                .build();
    }
}
