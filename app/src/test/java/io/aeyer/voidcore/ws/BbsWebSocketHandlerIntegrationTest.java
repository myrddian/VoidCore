package io.aeyer.voidcore.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end transport tests against a real Tomcat on a random port.
 *
 * <p>DB autoconfigs are excluded so the test does not need Postgres — the
 * transport layer is independent of the data layer. Heartbeat interval is
 * pushed to 60s to keep ping frames out of the assertions; heartbeat timing
 * itself is not unit-tested here (the scheduler is a few lines of obvious
 * code; meaningful coverage requires fault injection that is out of scope
 * for this transport milestone).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration",
                // AuthConfig is gated on spring.datasource.url being set; clear it
                // so the DB-less transport test still starts.
                "spring.datasource.url=",
                "voidcore.ws.heartbeat-seconds=60",
                "server.forward-headers-strategy=none"
        }
)
class BbsWebSocketHandlerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    SessionRegistry registry;

    private URI wsUri() {
        return URI.create("ws://localhost:" + port + "/ws");
    }

    @Test
    void subprotocolIsAdvertised() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            assertThat(client.session.getAcceptedProtocol()).isEqualTo("voidcore-node-v1");
        }
    }

    @Test
    void registryGrowsAndShrinksWithConnections() throws Exception {
        int before = registry.size();
        Client a = Client.connect(wsUri(), "voidcore-node-v1");
        Client b = Client.connect(wsUri(), "voidcore-node-v1");
        // Registry registration happens on afterConnectionEstablished, which
        // fires on the server's accept thread — give it a moment.
        waitUntil(() -> registry.size() == before + 2, Duration.ofSeconds(2));
        assertThat(registry.size()).isEqualTo(before + 2);
        a.close();
        b.close();
        waitUntil(() -> registry.size() == before, Duration.ofSeconds(2));
        assertThat(registry.size()).isEqualTo(before);
    }

    @Test
    void protocolVersionMismatchClosesWithError() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            ObjectNode payload = json.createObjectNode().put("foo", "bar");
            client.send(json.writeValueAsString(
                    new Envelope("msg-1", "auth.login", "totally-wrong-version", 0L, null, payload)));

            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.get("type").asText()).isEqualTo("error");
            assertThat(response.get("payload").get("code").asText())
                    .isEqualTo("PROTOCOL_VERSION_MISMATCH");
            assertThat(response.get("protocol_version").asText()).isEqualTo("voidcore-node-v1");

            CloseStatus status = client.awaitClose();
            assertThat(status.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
        }
    }

    @Test
    void unknownMessageTypeProducesValidationError() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            client.send(json.writeValueAsString(
                    new Envelope("msg-1", "totally.fake", "voidcore-node-v1",
                            0L, null, json.createObjectNode())));

            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.get("type").asText()).isEqualTo("error");
            assertThat(response.get("payload").get("code").asText()).isEqualTo("VALIDATION");
            assertThat(response.get("payload").get("message").asText()).contains("totally.fake");
            assertThat(client.session.isOpen()).isTrue();
        }
    }

    @Test
    void seqAndMacAreAcceptedAndIgnored() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            // SPEC §4.2: v1 servers accept and ignore seq + mac. We confirm the
            // server does not error on a non-zero seq or a populated mac (which
            // a v2 client might send if it speaks both versions). We use a
            // valid auth.logout payload (no required fields) so we exercise
            // the typed-dispatch path, not the unknown-type path.
            client.send(json.writeValueAsString(
                    new Envelope("msg-1", "auth.logout", "voidcore-node-v1",
                            42L, "would-be-mac-bytes", json.createObjectNode())));

            JsonNode response = json.readTree(client.awaitMessage());
            // Recognised + valid → NOT_IMPLEMENTED until the auth handler lands.
            // No PROTOCOL or VALIDATION error from the v2-shape envelope.
            assertThat(response.get("payload").get("code").asText()).isEqualTo("NOT_IMPLEMENTED");
            assertThat(client.session.isOpen()).isTrue();
        }
    }

    @Test
    void malformedJsonProducesValidationError() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            client.send("{this is not json");
            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.get("type").asText()).isEqualTo("error");
            assertThat(response.get("payload").get("code").asText()).isEqualTo("VALIDATION");
            assertThat(client.session.isOpen()).isTrue();
        }
    }

    @Test
    void recognisedAndValidPayloadReachesDispatcherAndYieldsNotImplemented() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            ObjectNode payload = json.createObjectNode();
            payload.put("handle", "TRINITY");
            payload.put("password", "correct horse battery staple");
            client.send(json.writeValueAsString(
                    new Envelope("login-1", "auth.login", "voidcore-node-v1", 0L, null, payload)));

            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.get("payload").get("code").asText()).isEqualTo("NOT_IMPLEMENTED");
            assertThat(response.get("payload").get("message").asText()).contains("AuthLogin");
        }
    }

    @Test
    void malformedTypedPayloadProducesValidationError() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            // auth.login has required fields handle + password; missing both
            // means jakarta.validation reports a NotBlank violation.
            client.send(json.writeValueAsString(
                    new Envelope("login-1", "auth.login", "voidcore-node-v1",
                            0L, null, json.createObjectNode())));

            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.get("payload").get("code").asText()).isEqualTo("VALIDATION");
            // Field path comes from jakarta.validation; should mention either
            // handle or password as the offending property.
            assertThat(response.get("payload").get("message").asText())
                    .containsAnyOf("handle", "password");
        }
    }

    @Test
    void invalidHandleFormatIsRejectedByValidation() throws Exception {
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            ObjectNode payload = json.createObjectNode();
            payload.put("handle", "bad name");  // contains space — fails the SPEC §3 regex
            payload.put("password", "correct horse battery staple");
            client.send(json.writeValueAsString(
                    new Envelope("login-1", "auth.login", "voidcore-node-v1", 0L, null, payload)));

            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.get("payload").get("code").asText()).isEqualTo("VALIDATION");
            assertThat(response.get("payload").get("message").asText()).contains("handle");
        }
    }

    @Test
    void outboundEnvelopeShapeMatchesSpec() throws Exception {
        // Verifies the wire shape SPEC §4.2 specifies: envelope carries id,
        // type, protocol_version, seq, mac at the top level; payload is
        // nested with no duplicate type field inside.
        try (Client client = Client.connect(wsUri(), "voidcore-node-v1")) {
            client.send(json.writeValueAsString(
                    new Envelope("logout-1", "auth.logout", "voidcore-node-v1",
                            0L, null, json.createObjectNode())));

            JsonNode response = json.readTree(client.awaitMessage());
            assertThat(response.has("id")).isTrue();
            assertThat(response.get("type").asText()).isEqualTo("error");
            assertThat(response.get("protocol_version").asText()).isEqualTo("voidcore-node-v1");
            assertThat(response.has("seq")).isTrue();
            assertThat(response.has("mac")).isTrue();
            assertThat(response.has("payload")).isTrue();
            // Crucial: payload must NOT carry a 'type' field — that lives on the envelope.
            assertThat(response.get("payload").has("type")).isFalse();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier check, Duration max)
            throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) return;
            Thread.sleep(20);
        }
    }

    /** Minimal text-only WS client used by these tests. */
    private static final class Client extends AbstractWebSocketHandler implements AutoCloseable {

        private final List<String> received = new CopyOnWriteArrayList<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private WebSocketSession session;

        static Client connect(URI uri, String subprotocol) throws Exception {
            Client c = new Client();
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("Sec-WebSocket-Protocol", subprotocol);
            c.session = client.execute(c, headers, uri).get(5, TimeUnit.SECONDS);
            return c;
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            synchronized (received) {
                received.add(message.getPayload());
                received.notifyAll();
            }
        }

        void send(String payload) throws Exception {
            session.sendMessage(new TextMessage(payload));
        }

        String awaitMessage() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            synchronized (received) {
                while (received.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("no message received");
                    received.wait(Math.max(1, remaining / 1_000_000));
                }
                return received.remove(0);
            }
        }

        CloseStatus awaitClose() throws Exception {
            return closed.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            if (session != null && session.isOpen()) session.close();
        }
    }
}
