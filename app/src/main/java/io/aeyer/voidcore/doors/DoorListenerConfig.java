package io.aeyer.voidcore.doors;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Optional internal listener for sidecar doors.
 *
 * <p>When {@code voidcore.doors.port} is set to a positive value different from
 * {@code server.port}, the app exposes an additional Tomcat connector on that
 * port. {@code /ws/door} is then handshake-gated to that listener so the door
 * protocol is not reachable via the public app port.
 */
@Configuration
public class DoorListenerConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> doorConnectorCustomizer(
            @Value("${voidcore.doors.port:0}") int doorPort,
            @Value("${server.port:8080}") int appPort,
            @Value("${voidcore.doors.address:127.0.0.1}") String doorAddress) {
        return factory -> {
            if (doorPort <= 0 || doorPort == appPort) return;

            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(doorPort);
            connector.setScheme("http");
            connector.setSecure(false);
            try {
                InetAddress address = InetAddress.getByName(doorAddress);
                connector.setProperty("address", address.getHostAddress());
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Invalid voidcore.doors.address: " + doorAddress, e);
            }
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
