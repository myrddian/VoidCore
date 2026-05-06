package io.aeyer.voidcore.ws.flow.screen.form;

import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MenuFormAppTest {

    static final class FakeState { String title = "hello"; }

    static final class FakeApp extends MenuFormApp<FakeState> {
        FakeState state = new FakeState();

        @Override public Phase phase() { return Phase.MENU; }
        @Override public String name() { return "fake"; }
        @Override protected String appKey(BbsContext ctx) { return "fake:1"; }
        @Override protected FakeState loadState(BbsContext ctx) { return state; }
        @Override protected String bannerLabel(BbsContext ctx, FakeState s) { return "FAKE"; }
        @Override protected Element headerElement(BbsContext ctx, FakeState s) {
            return new Element.Header("FAKE", null);
        }
        @Override
        protected List<FormField<FakeState>> fields(BbsContext ctx, FakeState s) {
            return List.of(new FormField<>(
                "T", "Title", st -> st.title, FieldKind.SINGLE_LINE,
                new FormField.FieldEditor<>() {
                    @Override public String onCommit(BbsContext c, FakeState st, String v) {
                        st.title = v; return null;
                    }
                }
            ));
        }
    }

    BbsContext ctx;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() {
        ctx = mock(BbsContext.class);
        sent = new ArrayList<>();
        doAnswer(inv -> { sent.add(inv.getArgument(0)); return null; })
                .when(ctx).send(any(ServerMessage.class));
    }

    private ServerMessage.InputPrompt lastInputPrompt() {
        ServerMessage.InputPrompt last = null;
        for (ServerMessage m : sent) {
            if (m instanceof ServerMessage.InputPrompt p) last = p;
        }
        return last;
    }

    @Test
    void enter_starts_in_VIEW_state() {
        new FakeApp().onEnter(ctx);
        assertThat(lastInputPrompt()).isNotNull();
        assertThat(lastInputPrompt().valid_keys()).contains("E");
    }

    @Test
    void E_keystroke_transitions_VIEW_to_EDIT_MENU() {
        FakeApp app = new FakeApp();
        app.onEnter(ctx);
        app.onKey(ctx, "E");
        assertThat(lastInputPrompt().valid_keys()).contains("T");
    }

    @Test
    void letter_in_EDIT_MENU_transitions_to_EDITING_FIELD() {
        FakeApp app = new FakeApp();
        app.onEnter(ctx);
        app.onKey(ctx, "E");
        app.onKey(ctx, "T");
        assertThat(lastInputPrompt().mode()).isEqualTo("none");
    }

    @Test
    void Q_in_VIEW_calls_popAndExit() {
        FakeApp app = new FakeApp();
        app.onEnter(ctx);
        app.onKey(ctx, "Q");
        verify(ctx).pop();
    }
}
