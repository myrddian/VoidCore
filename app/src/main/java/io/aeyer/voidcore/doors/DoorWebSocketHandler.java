package io.aeyer.voidcore.doors;

import io.aeyer.voidcore.ws.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;

@Component
@ConditionalOnBean(DoorRuntimeService.class)
public class DoorWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private final ObjectMapper json;
    private final DoorRuntimeService doors;

    public DoorWebSocketHandler(ObjectMapper json,
                                DoorRuntimeService doors) {
        this.json = json;
        this.doors = doors;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(DoorRuntimeService.PROTOCOL_VERSION);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        doors.handleConnect(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        doors.handleDisconnect(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Envelope inbound = json.readValue(message.getPayload(), Envelope.class);
        doors.handleEnvelope(session, inbound);
    }
}
