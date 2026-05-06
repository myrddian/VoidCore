package io.aeyer.voidcore.ws;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Registers the BBS WebSocket endpoint at {@code /ws}. Enables Spring's
 * scheduling so {@link HeartbeatScheduler} fires.
 *
 * <p>The handler advertises its supported subprotocol via
 * {@link BbsWebSocketHandler#getSubProtocols()}; Spring's default handshake
 * handler reads that to negotiate {@code Sec-WebSocket-Protocol}.
 *
 * <p>{@code setAllowedOriginPatterns("*")} is correct for v1 because the
 * upstream DMZ Caddy is the only public face — internal traffic is on a
 * trusted network (SPEC §2.1). If the BBS app were ever exposed directly,
 * this should be tightened to {@code VOIDCORE_PUBLIC_URL}'s origin.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class VoidCoreWebSocketConfig implements WebSocketConfigurer {

    private final BbsWebSocketHandler handler;
    private final ObjectProvider<io.aeyer.voidcore.doors.DoorWebSocketHandler> doorHandler;
    private final ObjectProvider<io.aeyer.voidcore.doors.DoorPortHandshakeInterceptor> doorPortHandshakeInterceptor;

    public VoidCoreWebSocketConfig(BbsWebSocketHandler handler,
                                ObjectProvider<io.aeyer.voidcore.doors.DoorWebSocketHandler> doorHandler,
                                ObjectProvider<io.aeyer.voidcore.doors.DoorPortHandshakeInterceptor> doorPortHandshakeInterceptor) {
        this.handler = handler;
        this.doorHandler = doorHandler;
        this.doorPortHandshakeInterceptor = doorPortHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
        io.aeyer.voidcore.doors.DoorWebSocketHandler resolvedDoorHandler = doorHandler.getIfAvailable();
        io.aeyer.voidcore.doors.DoorPortHandshakeInterceptor resolvedDoorInterceptor =
                doorPortHandshakeInterceptor.getIfAvailable();
        if (resolvedDoorHandler != null) {
            var registration = registry.addHandler(resolvedDoorHandler, "/ws/door")
                    .setAllowedOriginPatterns("*");
            if (resolvedDoorInterceptor != null) {
                registration.addInterceptors(resolvedDoorInterceptor);
            }
        }
    }

    /**
     * Raises Tomcat's per-message WebSocket buffer ceiling above the 8 KB default.
     *
     * <p>The default truncates oversized messages and closes the session, which
     * the door-side SDK reports as a transport drop. Headroom we need:
     * <ul>
     *   <li>full-screen ANSI paints (region.update) at large viewports run several KB,</li>
     *   <li>door-→BBS LLM-chat envelopes carry the full prompt and grow with
     *       conversation context — untrimmed prompts can be hundreds of KB.</li>
     * </ul>
     *
     * <p>1 MB ceiling. Worst-case peak memory ~100 MB at 100 concurrent
     * connections, trivial against the host's gigabytes. If a frame is ever
     * legitimately larger than 1 MB it should be split (delta paints, paginated
     * data) rather than sent as one envelope.
     */
    @Bean
    public ServletServerContainerFactoryBean wsContainerFactory() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1048576);
        container.setMaxBinaryMessageBufferSize(1048576);
        return container;
    }
}
