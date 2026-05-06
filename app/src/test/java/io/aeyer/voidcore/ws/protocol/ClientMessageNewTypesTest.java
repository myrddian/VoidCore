package io.aeyer.voidcore.ws.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientMessageNewTypesTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ProtocolTypeRegistry registry = new ProtocolTypeRegistry();

    @Test
    void editorCommitDeserialises() throws Exception {
        String s = "{\"widget_id\":\"body\",\"content\":\"hello\",\"action\":\"save\"}";
        JsonNode tree = json.readTree(s);
        Class<? extends ClientMessage> cls = registry.clientClassFor("editor.commit").orElseThrow();
        ClientMessage m = json.treeToValue(tree, cls);
        assertThat(m).isEqualTo(new ClientMessage.EditorCommit("body", "hello", "save"));
    }

    @Test
    void editorCancelDeserialises() throws Exception {
        String s = "{\"widget_id\":\"body\",\"force\":true}";
        JsonNode tree = json.readTree(s);
        Class<? extends ClientMessage> cls = registry.clientClassFor("editor.cancel").orElseThrow();
        ClientMessage m = json.treeToValue(tree, cls);
        assertThat(m).isEqualTo(new ClientMessage.EditorCancel("body", true));
    }

    @Test
    void editorSnapshotDeserialises() throws Exception {
        String s = "{\"widget_id\":\"body\",\"content\":\"x\"}";
        JsonNode tree = json.readTree(s);
        Class<? extends ClientMessage> cls = registry.clientClassFor("editor.snapshot").orElseThrow();
        ClientMessage m = json.treeToValue(tree, cls);
        assertThat(m).isEqualTo(new ClientMessage.EditorSnapshot("body", "x"));
    }

    @Test
    void fieldCommitDeserialises() throws Exception {
        String s = "{\"widget_id\":\"title\",\"value\":\"hi\"}";
        JsonNode tree = json.readTree(s);
        Class<? extends ClientMessage> cls = registry.clientClassFor("field.commit").orElseThrow();
        ClientMessage m = json.treeToValue(tree, cls);
        assertThat(m).isEqualTo(new ClientMessage.FieldCommit("title", "hi"));
    }

    @Test
    void focusMoveDeserialises() throws Exception {
        String s = "{\"from\":\"title\",\"direction\":\"next\"}";
        JsonNode tree = json.readTree(s);
        Class<? extends ClientMessage> cls = registry.clientClassFor("focus.move").orElseThrow();
        ClientMessage m = json.treeToValue(tree, cls);
        assertThat(m).isEqualTo(new ClientMessage.FocusMove("title", "next"));
    }
}
