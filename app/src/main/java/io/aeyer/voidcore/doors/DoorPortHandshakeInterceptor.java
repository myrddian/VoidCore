package io.aeyer.voidcore.doors;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Restricts /ws/door to the dedicated internal listener when configured.
 */
@Component
public class DoorPortHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DoorPortHandshakeInterceptor.class);

    private final int doorPort;
    private final int appPort;

    public DoorPortHandshakeInterceptor(@Value("${voidcore.doors.port:0}") int doorPort,
                                        @Value("${server.port:8080}") int appPort) {
        this.doorPort = doorPort;
        this.appPort = appPort;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (doorPort <= 0 || doorPort == appPort) {
            return true;
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        HttpServletRequest raw = servletRequest.getServletRequest();
        int localPort = raw.getLocalPort();
        if (localPort == doorPort) {
            return true;
        }
        log.warn("door-handshake-denied localPort={} expectedDoorPort={}", localPort, doorPort);
        response.setStatusCode(HttpStatus.NOT_FOUND);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
