package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.protocol.ProtocolTypeRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code list.selected} on the wire, and its mapping onto the internal
 * {@link AppEvent} union.
 *
 * <p>What matters here is that the wire name and field names are exactly
 * what the client sends — a mismatch deserialises to nulls rather than
 * failing loudly, so it is worth pinning.
 */
class ScreenRouterListSelectedTest {

    private final ObjectMapper json = new ObjectMapper();

    private final ProtocolTypeRegistry registry = new ProtocolTypeRegistry();

    @Test
    void theRegistryResolvesTheWireName() {
        // Inbound parsing is two-pass (SPEC §4.2): the envelope's `type`
        // goes through the registry, then the payload is read into that
        // class. A name the registry doesn't know is rejected as unknown,
        // so this is the wiring that matters.
        assertThat(registry.clientClassFor("list.selected"))
                .contains(ClientMessage.ListSelected.class);
    }

    @Test
    void deserialisesThePayloadTheClientSends() throws Exception {
        // Exactly the payload widgets/list.ts puts on the wire.
        var payload = json.readTree("""
                {"widget_id":"bases","item_id":"general"}
                """);

        var cls = registry.clientClassFor("list.selected").orElseThrow();
        ClientMessage m = json.treeToValue(payload, cls);

        assertThat(m).isInstanceOf(ClientMessage.ListSelected.class);
        ClientMessage.ListSelected ls = (ClientMessage.ListSelected) m;
        // Snake-case field names: a mismatch here binds nulls silently
        // rather than failing, which is why it is pinned.
        assertThat(ls.widget_id()).isEqualTo("bases");
        assertThat(ls.item_id()).isEqualTo("general");
    }

    @Test
    void carriesAnItemIdRatherThanAnIndex() {
        // Screens key off identity, so a list that reorders between paints
        // cannot select the wrong row.
        AppEvent.ListSelected ev = new AppEvent.ListSelected("bases", "releases");

        assertThat(ev.widgetId()).isEqualTo("bases");
        assertThat(ev.itemId()).isEqualTo("releases");
    }
}
