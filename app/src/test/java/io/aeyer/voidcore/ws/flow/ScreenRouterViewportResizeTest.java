package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.auth.AuthService;
import io.aeyer.voidcore.auth.SessionService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Frame;
import io.aeyer.voidcore.ws.flow.screen.NavigationState;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScreenRouter#onViewportResize} — the server half of
 * {@code viewport.resize} (SPEC §4.3 / §6.7).
 *
 * <p>The size lands on the session, then the active screen is offered a
 * chance to reflow. The important negative is that a resize must NOT
 * re-enter the screen: {@code onEnter} resets screen-local state, so a
 * user rotating their phone mid-wizard would be thrown back to step 0.
 */
class ScreenRouterViewportResizeTest {

    /** Records which lifecycle hooks fire, so we can assert on the
     *  difference between "reflowed" and "re-entered". */
    static class RecordingScreen implements Screen {
        int enters = 0;
        int resizes = 0;
        Integer colsSeenDuringResize = null;

        @Override public Transition onEnter(BbsContext ctx) {
            enters++;
            return Transition.None.INSTANCE;
        }

        @Override public void onViewportResize(BbsContext ctx) {
            resizes++;
            colsSeenDuringResize = ctx.viewportCols();
        }

        @Override public Phase phase() { return Phase.MENU; }

        @Override public String name() { return "recording-screen"; }
    }

    ScreenRouter router;
    VoidCoreSession session;
    NavigationState navState;
    RecordingScreen screen;

    @BeforeEach
    void setUp() throws java.io.IOException {
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

        router = new ScreenRouter(
                auth, sessionService, users, netmail,
                messageBases, threads, presence,
                new ObjectMapper(), wsSessions, bbsServices, bus,
                navState, appCtx, List.of());

        session = mock(VoidCoreSession.class);
        when(session.id()).thenReturn("test-session-resize");
        when(session.userId()).thenReturn(null);
        doAnswer(inv -> null).when(session).send(any());

        screen = new RecordingScreen();
        when(navState.peekFrame(session)).thenReturn(new Frame(Phase.MENU, screen));
    }

    @Test
    void recordsTheReportedSizeOnTheSession() {
        router.onViewportResize(session, new ClientMessage.ViewportResize(64, 20));

        verify(session).setViewport(64, 20);
    }

    @Test
    void offersTheActiveScreenAChanceToReflow() {
        router.onViewportResize(session, new ClientMessage.ViewportResize(120, 40));

        assertThat(screen.resizes).isEqualTo(1);
    }

    @Test
    void doesNotReEnterTheScreen() {
        // onEnter resets screen-local state — a wizard's step index, a
        // form's accumulated draft. Rotating a phone must not do that.
        router.onViewportResize(session, new ClientMessage.ViewportResize(64, 20));

        assertThat(screen.enters).isZero();
    }

    @Test
    void theNewSizeIsVisibleToTheScreenWhenItReflows() {
        // Order matters: the session is updated before the hook fires, so
        // a screen composing against ctx.viewportCols() sees the new width
        // rather than the previous one.
        when(session.viewportCols()).thenReturn(64);

        router.onViewportResize(session, new ClientMessage.ViewportResize(64, 20));

        assertThat(screen.colsSeenDuringResize).isEqualTo(64);
    }

    @Test
    void toleratesAnEmptyNavigationStack() throws java.io.IOException {
        // Resize can arrive before the first screen is pushed — the client
        // reports on connect. Record the size, skip the hook, don't throw.
        when(navState.peekFrame(session)).thenReturn(null);

        router.onViewportResize(session, new ClientMessage.ViewportResize(100, 30));

        verify(session).setViewport(100, 30);
        verify(session, never()).send(any());
    }

    @Test
    void defaultScreensIgnoreResizeEntirely() throws java.io.IOException {
        // Screen.onViewportResize defaults to a no-op, so screens whose
        // layout the client owns cost nothing on resize.
        Screen plain = new Screen() {
            @Override public Transition onEnter(BbsContext ctx) { return Transition.None.INSTANCE; }
            @Override public Phase phase() { return Phase.MENU; }
            @Override public String name() { return "plain"; }
        };
        when(navState.peekFrame(session)).thenReturn(new Frame(Phase.MENU, plain));

        router.onViewportResize(session, new ClientMessage.ViewportResize(64, 20));

        verify(session).setViewport(64, 20);
        verify(session, never()).send(any());
    }
}
