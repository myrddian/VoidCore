package io.aeyer.voidcore.ws.flow.layout;

import io.aeyer.voidcore.ws.flow.ui.FocusPath;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code list} element — first piece of the v2 layout vocabulary
 * (ADR-035). Covers the wire contract and its place in the tree.
 */
class ListViewElementTest {

    private final ObjectMapper json = new ObjectMapper();

    private Element.ListView sample() {
        return new Element.ListView("bases", List.of(
                new Element.ListView.Item("general", "General Chatter", "12 unread"),
                new Element.ListView.Item("meta", "Meta")), "meta");
    }

    @Test
    void serialisesUnderTheListDiscriminator() throws Exception {
        String wire = json.writeValueAsString(sample());

        // The client dispatches on "kind"; ListView is named only to avoid
        // colliding with java.util.List at call sites.
        assertThat(wire).contains("\"kind\":\"list\"");
        assertThat(wire).contains("\"id\":\"bases\"");
        assertThat(wire).contains("\"selectedId\":\"meta\"");
    }

    @Test
    void roundTripsThroughTheWire() throws Exception {
        Element back = json.readValue(json.writeValueAsString(sample()), Element.class);

        assertThat(back).isInstanceOf(Element.ListView.class);
        Element.ListView lv = (Element.ListView) back;
        assertThat(lv.items()).hasSize(2);
        assertThat(lv.items().get(0).secondary()).isEqualTo("12 unread");
        assertThat(lv.items().get(1).secondary()).isNull();
    }

    @Test
    void isFocusable() {
        // A list takes focus like any other interactive widget, so a screen
        // whose only widget is a list still gets a focus path.
        assertThat(FocusPath.firstFocusable(sample())).contains("bases");
    }

    @Test
    void isFoundInsideAContainer() {
        Element tree = new Element.VStack(List.of(
                new Element.Header("BASES", null),
                sample()), 0);

        assertThat(FocusPath.firstFocusable(tree)).contains("bases");
    }

    @Test
    void rendersNoRowsInFixedMode() {
        // Tree-only widget, like the other five: the client paints it. It
        // must not throw when one lands in a row-rendered layout.
        List<io.aeyer.voidcore.ws.protocol.ServerMessage.Row> rows =
                LayoutRenderer.render(new Layout.Flow(sample(), 80));

        assertThat(rows).isEmpty();
    }
}
