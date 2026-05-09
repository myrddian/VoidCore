package io.aeyer.voidcore.ws.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.auth.Session;
import io.aeyer.voidcore.auth.AuthService;
import io.aeyer.voidcore.auth.SessionService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import io.aeyer.voidcore.messages.MessageBaseRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.presence.PresenceService;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import io.aeyer.voidcore.ws.protocol.ClientMessage;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.CustomScreenProvider;
import io.aeyer.voidcore.ws.flow.screen.CustomScreenRegistry;
import io.aeyer.voidcore.ws.flow.screen.NavigationState;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.screen.ScreenRoute;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreenRouterCustomScreenTest {

    static class CustomApp extends ScreenApp {
        static final AtomicInteger instanceCount = new AtomicInteger();

        final int instanceId = instanceCount.incrementAndGet();
        int appEvents = 0;
        int keyEvents = 0;
        int lineEvents = 0;
        int cancelEvents = 0;

        @Override public Phase phase() { return Phase.MENU; }
        @Override public String name() { return "custom-host"; }
        @Override protected String appKey(BbsContext ctx) { return "custom:" + instanceId; }

        @Override
        protected Element compose(BbsContext ctx) {
            return new Element.Form("f", List.of(
                    new Element.TextField("title", "title:", "", null, false)
            ), "title");
        }

        @Override
        protected void onEvent(BbsContext ctx, AppEvent ev) {
            appEvents++;
        }

        @Override
        public Transition onKey(BbsContext ctx, String key) {
            keyEvents++;
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onLine(BbsContext ctx, String text) {
            lineEvents++;
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onCancel(BbsContext ctx) {
            cancelEvents++;
            return Transition.None.INSTANCE;
        }
    }

    private record Harness(ScreenRouter router, SessionService sessions) {}

    private Harness buildHarness(NavigationState navState, CustomScreenRegistry registry) {
        SessionService sessions = mock(SessionService.class);
        ScreenRouter router = new ScreenRouter(
                mock(AuthService.class),
                sessions,
                mock(UserRepository.class),
                mock(NetmailRepository.class),
                mock(MessageBaseRepository.class),
                mock(ThreadRepository.class),
                mock(PresenceService.class),
                new ObjectMapper(),
                mock(SessionRegistry.class),
                mock(BbsServices.class),
                mock(MessageBus.class),
                navState,
                mock(ApplicationContext.class),
                List.<Screen>of(),
                registry);
        return new Harness(router, sessions);
    }

    private VoidCoreSession sessionWithId(String id) throws Exception {
        VoidCoreSession session = mock(VoidCoreSession.class);
        when(session.id()).thenReturn(id);
        when(session.userId()).thenReturn(1L);
        doAnswer(inv -> null).when(session).send(any());
        return session;
    }

    @Test
    void pushStringCreatesCustomRouteFrameAndLeavesPhaseShimNull() throws Exception {
        NavigationState navState = new NavigationState();
        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(new CustomScreenProvider() {
                    @Override public String screenName() { return "aeyer/releases"; }
                    @Override public Screen createScreen() { return new CustomApp(); }
                }),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), null));
        Harness harness = buildHarness(navState, registry);
        VoidCoreSession session = sessionWithId("custom-route-a");

        harness.router().push(session, "aeyer/releases");

        var frame = navState.peekFrame(session);
        assertThat(frame).isNotNull();
        assertThat(frame.route()).isInstanceOf(ScreenRoute.Custom.class);
        assertThat(frame.route().key()).isEqualTo("aeyer/releases");
        assertThat(frame.phase()).isNull();
        assertThat(navState.currentPhase(session)).isNull();
    }

    @Test
    void appEventsStillDispatchWhenTopFrameIsCustomRouted() throws Exception {
        NavigationState navState = new NavigationState();
        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(new CustomScreenProvider() {
                    @Override public String screenName() { return "aeyer/releases"; }
                    @Override public Screen createScreen() { return new CustomApp(); }
                }),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), null));
        Harness harness = buildHarness(navState, registry);
        VoidCoreSession session = sessionWithId("custom-route-b");

        harness.router().push(session, "aeyer/releases");
        CustomApp top = (CustomApp) navState.peekFrame(session).screen();

        harness.router().onAppEvent(session, new AppEvent.FieldCommit("title", "hello"));

        assertThat(top.appEvents).isEqualTo(1);
    }

    @Test
    void keyLineAndCancelDispatchStillReachCustomRoutedScreen() throws Exception {
        NavigationState navState = new NavigationState();
        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(new CustomScreenProvider() {
                    @Override public String screenName() { return "aeyer/releases"; }
                    @Override public Screen createScreen() { return new CustomApp(); }
                }),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), null));
        Harness harness = buildHarness(navState, registry);
        VoidCoreSession session = sessionWithId("custom-route-dispatch");

        harness.router().push(session, "aeyer/releases");
        CustomApp top = (CustomApp) navState.peekFrame(session).screen();

        harness.router().onKeystroke(session, new ClientMessage.Keystroke("X"));
        harness.router().onLineSubmit(session, new ClientMessage.LineSubmit("hello"));
        harness.router().onLineCancel(session);

        assertThat(top.keyEvents).isEqualTo(1);
        assertThat(top.lineEvents).isEqualTo(1);
        assertThat(top.cancelEvents).isEqualTo(1);
    }

    @Test
    void applyPostAuthRestoresCustomScreenFromCurrentScreen() throws Exception {
        NavigationState navState = new NavigationState();
        CustomScreenRegistry registry = new CustomScreenRegistry(
                List.of(new CustomScreenProvider() {
                    @Override public String screenName() { return "aeyer/releases"; }
                    @Override public Screen createScreen() { return new CustomApp(); }
                }),
                new ObjectMapper(),
                new InstanceFeatureProperties(List.of(), null));
        Harness harness = buildHarness(navState, registry);
        ObjectMapper json = new ObjectMapper();
        VoidCoreSession session = sessionWithId("custom-route-resume");
        when(session.sessionToken()).thenReturn("token-1");
        when(harness.sessions().peek("token-1")).thenReturn(java.util.Optional.of(
                new Session(
                        "token-1",
                        1L,
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(1),
                        "127.0.0.1",
                        "JUnit",
                        json.readTree("{\"kind\":\"custom_screen\",\"screen\":\"aeyer/releases\"}")
                )));

        harness.router().applyPostAuth(session, new UserRow(1L, "enzo", "pw", false, false));

        assertThat(navState.snapshotFramesBottomToTop(session))
                .extracting(frame -> frame.route().key())
                .containsExactly("MENU", "aeyer/releases");
    }

    @Test
    void applyPostAuthFallsBackToMenuWhenCustomScreenIsMissing() throws Exception {
        NavigationState navState = new NavigationState();
        Harness harness = buildHarness(navState, CustomScreenRegistry.empty());
        ObjectMapper json = new ObjectMapper();
        VoidCoreSession session = sessionWithId("custom-route-missing");
        when(session.sessionToken()).thenReturn("token-2");
        when(harness.sessions().peek("token-2")).thenReturn(java.util.Optional.of(
                new Session(
                        "token-2",
                        1L,
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(1),
                        "127.0.0.1",
                        "JUnit",
                        json.readTree("{\"kind\":\"custom_screen\",\"screen\":\"missing/screen\"}")
                )));

        harness.router().applyPostAuth(session, new UserRow(1L, "enzo", "pw", false, false));

        assertThat(navState.snapshotFramesBottomToTop(session))
                .extracting(frame -> frame.route().key())
                .containsExactly("MENU");
    }
}
