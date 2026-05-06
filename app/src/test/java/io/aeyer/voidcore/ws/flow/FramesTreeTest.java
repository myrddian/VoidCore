package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.protocol.ServerMessage.RegionUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FramesTreeTest {

    @Test
    void treeBuildsRegionUpdateWithTreePayload() {
        Element body = new Element.VStack(List.of(
                new Element.Header("DOC", "pattern9"),
                new Element.TextField("title", "title:", "x", 200, false)
        ), 0);
        RegionUpdate ru = Frames.tree("main", 1, body, "title");
        assertThat(ru.region()).isEqualTo("main");
        assertThat(ru.version()).isEqualTo(1);
        assertThat(ru.tree()).isEqualTo(body);
        assertThat(ru.focus()).isEqualTo("title");
        assertThat(ru.content()).isNull();
    }

    @Test
    void treePayloadSerialisesWithKindDiscriminator() throws Exception {
        Element h = new Element.Header("DOC", null);
        RegionUpdate ru = Frames.tree("main", 7, h, null);
        String s = new ObjectMapper().writeValueAsString(ru);
        assertThat(s).contains("\"tree\":");
        assertThat(s).contains("\"kind\":\"header\"");
        assertThat(s).contains("\"region\":\"main\"");
    }

    @Test
    void rowsPayloadOmitsTreeAndFocusFields() throws Exception {
        Frames.row(0, Frames.span("hi", "default"));
        var ru = Frames.update("main", 1,
            java.util.List.of(Frames.text(0, "hi")));
        String s = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ru);
        assertThat(s).doesNotContain("\"tree\":");
        assertThat(s).doesNotContain("\"focus\":");
    }
}
