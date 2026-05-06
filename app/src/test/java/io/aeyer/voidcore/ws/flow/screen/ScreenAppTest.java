package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.RegionUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class ScreenAppTest {

    /** Tiny test screen with one TextField. Records onEvent calls. */
    static class FakeApp extends ScreenApp {
        final List<AppEvent> received = new ArrayList<>();
        @Override protected String appKey(BbsContext ctx) { return "test:1"; }
        @Override protected Element compose(BbsContext ctx) {
            return new Element.Form("f", List.of(
                    new Element.TextField("title", "title:", "x", 200, false)
            ), "title");
        }
        @Override protected void onEvent(BbsContext ctx, AppEvent ev) { received.add(ev); }
        @Override public Phase phase() { return Phase.MENU; /* placeholder for the test */ }
        @Override public String name() { return "fake-app"; }
    }

    @Test
    void onEnterEmitsTreeUpdateAndSetsFocusToFirstFocusable() {
        BbsContext ctx = mock(BbsContext.class);
        List<ServerMessage> sent = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));
        new FakeApp().onEnter(ctx);
        // onEnter sends: (1) tree RegionUpdate, (2) banner RegionUpdate (minimal),
        // (3) InputPrompt("none") to silence the InputController while the editor
        // widget owns the keyboard.
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isInstanceOf(RegionUpdate.class);
        RegionUpdate ru = (RegionUpdate) sent.get(0);
        assertThat(ru.tree()).isInstanceOf(Element.Form.class);
        assertThat(ru.focus()).isEqualTo("title");
        assertThat(sent.get(1)).isInstanceOf(RegionUpdate.class);
        assertThat(((RegionUpdate) sent.get(1)).region()).isEqualTo("banner");
        assertThat(sent.get(2)).isInstanceOf(ServerMessage.InputPrompt.class);
        assertThat(((ServerMessage.InputPrompt) sent.get(2)).mode()).isEqualTo("none");
    }

    @Test
    void onAppEventCallsOnEventThenRepaints() {
        BbsContext ctx = mock(BbsContext.class);
        List<ServerMessage> sent = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));
        FakeApp app = new FakeApp();
        app.onEnter(ctx);            // 1 paint
        sent.clear();
        AppEvent ev = new AppEvent.FieldCommit("title", "new");
        app.onAppEvent(ctx, ev);
        assertThat(app.received).containsExactly(ev);
        assertThat(sent).hasSize(1); // repaint after onEvent
    }
}
