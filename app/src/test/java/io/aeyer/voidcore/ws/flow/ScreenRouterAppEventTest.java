package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.auth.AuthService;
import io.aeyer.voidcore.auth.SessionService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Frame;
import io.aeyer.voidcore.ws.flow.screen.NavigationState;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AppEvent dispatch path: {@link ScreenRouter#onAppEvent}
 * routes the five wire message types (EditorCommit, EditorCancel,
 * EditorSnapshot, FieldCommit, FocusMove) to the active ScreenApp's onEvent
 * handler.
 *
 * <p>Post-2026-05 the router reads the active screen from the
 * {@link NavigationState} top frame rather than from a global
 * Phase→Screen map. The test stubs {@code navState.peekFrame(session)} to
 * return a frame containing a {@link CapturingApp}; no reflection into
 * router internals required.
 */
class ScreenRouterAppEventTest {

    /** Minimal ScreenApp that records every AppEvent it receives. */
    static class CapturingApp extends ScreenApp {
        final List<AppEvent> received = new ArrayList<>();

        @Override protected String appKey(BbsContext ctx) { return "test:app"; }

        @Override protected Element compose(BbsContext ctx) {
            return new Element.Form("f", List.of(
                    new Element.TextField("title", "title:", "x", null, false)
            ), "title");
        }

        @Override protected void onEvent(BbsContext ctx, AppEvent ev) { received.add(ev); }

        @Override public Phase phase() { return Phase.MENU; }

        @Override public String name() { return "capturing-app"; }
    }

    ScreenRouter router;
    VoidCoreSession session;
    NavigationState navState;
    CapturingApp app;

    @BeforeEach
    void setUp() throws java.io.IOException {
        // Mock all constructor dependencies — none of them do real work
        // for AppEvent dispatch except navState (which we control).
        AuthService auth = mock(AuthService.class);
        SessionService sessionService = mock(SessionService.class);
        UserRepository users = mock(UserRepository.class);
        NetmailRepository netmail = mock(NetmailRepository.class);
        MessageBaseRepository messageBases = mock(MessageBaseRepository.class);
        ThreadRepository threads = mock(ThreadRepository.class);
        PresenceService presence = mock(PresenceService.class);
        SessionRegistry wsSessions = mock(SessionRegistry.class);
        BbsServices bbsServices = mock(BbsServices.class);
        MessageBus bus = mock(MessageBus.class);
        navState = mock(NavigationState.class);
        ApplicationContext appCtx = mock(ApplicationContext.class);

        // Build ScreenRouter with an empty Screen list — we'll stub the
        // active frame directly via navState. The router's screenProviders
        // map stays empty (no push/pop will be exercised).
        router = new ScreenRouter(
                auth, sessionService, users, netmail,
                messageBases, threads, presence,
                new ObjectMapper(), wsSessions, bbsServices, bus,
                navState, appCtx, List.of());

        session = mock(VoidCoreSession.class);
        when(session.id()).thenReturn("test-session-1");
        when(session.userId()).thenReturn(null);
        doAnswer(inv -> null).when(session).send(any());

        app = new CapturingApp();
        // Stub the top-of-stack frame so dispatch finds CapturingApp.
        when(navState.peekFrame(session)).thenReturn(new Frame(Phase.MENU, app));
    }

    @Test
    void editorCommitIsRoutedToOnEvent() {
        router.onAppEvent(session, new AppEvent.EditorCommit("body", "hello", "save"));

        assertThat(app.received).hasSize(1);
        AppEvent.EditorCommit ec = (AppEvent.EditorCommit) app.received.get(0);
        assertThat(ec.widgetId()).isEqualTo("body");
        assertThat(ec.content()).isEqualTo("hello");
        assertThat(ec.action()).isEqualTo("save");
    }

    @Test
    void editorCancelIsRoutedToOnEvent() {
        router.onAppEvent(session, new AppEvent.EditorCancel("body", true));

        assertThat(app.received).hasSize(1);
        AppEvent.EditorCancel ec = (AppEvent.EditorCancel) app.received.get(0);
        assertThat(ec.widgetId()).isEqualTo("body");
        assertThat(ec.force()).isTrue();
    }

    @Test
    void editorSnapshotIsRoutedToOnEvent() {
        router.onAppEvent(session, new AppEvent.EditorSnapshot("body", "draft text"));

        assertThat(app.received).hasSize(1);
        AppEvent.EditorSnapshot es = (AppEvent.EditorSnapshot) app.received.get(0);
        assertThat(es.widgetId()).isEqualTo("body");
        assertThat(es.content()).isEqualTo("draft text");
    }

    @Test
    void fieldCommitIsRoutedToOnEvent() {
        router.onAppEvent(session, new AppEvent.FieldCommit("title", "new value"));

        assertThat(app.received).hasSize(1);
        AppEvent.FieldCommit fc = (AppEvent.FieldCommit) app.received.get(0);
        assertThat(fc.widgetId()).isEqualTo("title");
        assertThat(fc.value()).isEqualTo("new value");
    }

    @Test
    void focusMoveIsRoutedToOnEvent() {
        router.onAppEvent(session, new AppEvent.FocusMove("title", "down"));

        assertThat(app.received).hasSize(1);
        AppEvent.FocusMove fm = (AppEvent.FocusMove) app.received.get(0);
        assertThat(fm.from()).isEqualTo("title");
        assertThat(fm.direction()).isEqualTo("down");
    }

    @Test
    void appEventIsDroppedWhenActiveScreenIsNotAScreenApp() {
        // Replace the top frame with one carrying a plain Screen mock.
        Screen plainScreen = mock(Screen.class);
        when(plainScreen.name()).thenReturn("plain-screen");
        when(plainScreen.phase()).thenReturn(Phase.MENU);
        when(navState.peekFrame(session)).thenReturn(new Frame(Phase.MENU, plainScreen));

        // Should not throw; event is silently dropped with a debug log.
        router.onAppEvent(session, new AppEvent.FieldCommit("title", "ignored"));

        assertThat(app.received).isEmpty();
    }

    @Test
    void appEventIsDroppedWhenNoFrameForSession() {
        // Empty stack — no frame for this session.
        when(navState.peekFrame(session)).thenReturn(null);

        router.onAppEvent(session, new AppEvent.FieldCommit("title", "ignored"));

        assertThat(app.received).isEmpty();
    }
}
