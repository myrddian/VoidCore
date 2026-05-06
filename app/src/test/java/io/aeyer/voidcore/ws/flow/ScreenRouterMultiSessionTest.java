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
import io.aeyer.voidcore.ws.flow.screen.NavigationState;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenApp;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the multi-session state-leak bug fixed by
 * {@link io.aeyer.voidcore.ws.flow.screen.ScreenAppComponent}.
 *
 * <p>Before the fix, a stateful {@link ScreenApp} was a singleton Spring
 * bean: every session that pushed the same Phase shared one instance,
 * and instance fields like {@code uiState} / {@code editingLetter} /
 * {@code stepIndex} stomped each other across sessions. After the fix:
 * <ul>
 *   <li>{@code @ScreenAppComponent} is prototype-scoped, so each push
 *       mints a fresh instance.</li>
 *   <li>{@link io.aeyer.voidcore.ws.flow.screen.NavigationState} pins the
 *       minted instance to the navigator-stack {@link
 *       io.aeyer.voidcore.ws.flow.screen.Frame} that minted it.</li>
 *   <li>The router dispatches every event through {@code
 *       navState.peekFrame(session).screen()} — never through a global
 *       map.</li>
 * </ul>
 *
 * <p>This test exercises that contract end-to-end (with a mocked Spring
 * {@link ApplicationContext} substituting for a real container) and
 * asserts that two sessions in the same Phase get distinct instances
 * and don't see each other's state.
 */
class ScreenRouterMultiSessionTest {

    /**
     * Stateful test ScreenApp. Counts how many distinct instances ever
     * exist (so we can prove the prototype-scoped path mints fresh
     * instances on each push) and carries a per-instance counter
     * (so we can prove state doesn't leak across sessions).
     */
    static class StatefulApp extends ScreenApp {
        static final AtomicInteger instanceCount = new AtomicInteger();
        final int instanceId = instanceCount.incrementAndGet();
        int eventsReceived = 0;
        final List<AppEvent> seen = new ArrayList<>();

        @Override public Phase phase() { return Phase.MENU; }
        @Override public String name() { return "stateful-app"; }
        @Override protected String appKey(BbsContext ctx) { return "stateful:" + instanceId; }
        @Override protected Element compose(BbsContext ctx) {
            return new Element.Form("f", List.of(
                    new Element.TextField("x", "x:", "", null, false)
            ), "x");
        }
        @Override protected void onEvent(BbsContext ctx, AppEvent ev) {
            eventsReceived++;
            seen.add(ev);
        }
    }

    private record Harness(ScreenRouter router, NavigationState navState, ApplicationContext appCtx) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Harness build() {
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

        // Real NavigationState — the dispatch path reads it on every
        // event, so a mock would obscure the multi-session behaviour
        // we're trying to verify.
        NavigationState navState = new NavigationState();

        // Mock ApplicationContext that returns a fresh StatefulApp every
        // time the router resolves the bean — emulating prototype scope.
        ApplicationContext appCtx = mock(ApplicationContext.class);
        ObjectProvider<StatefulApp> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenAnswer(inv -> new StatefulApp());
        when(appCtx.getBeanProvider(StatefulApp.class)).thenReturn((ObjectProvider) provider);

        // Use a sentinel StatefulApp so the constructor's phase-discovery
        // loop registers the provider for Phase.MENU.
        ScreenRouter router = new ScreenRouter(
                auth, sessionService, users, netmail,
                messageBases, threads, presence,
                new ObjectMapper(), wsSessions, bbsServices, bus,
                navState, appCtx, List.of(new StatefulApp()));

        return new Harness(router, navState, appCtx);
    }

    private VoidCoreSession sessionWithId(String id) {
        VoidCoreSession s = mock(VoidCoreSession.class);
        when(s.id()).thenReturn(id);
        when(s.userId()).thenReturn(1L);
        try {
            doAnswer(inv -> null).when(s).send(any());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);  // mock setup, never throws
        }
        return s;
    }

    @Test
    void twoSessionsPushingSamePhaseGetDistinctInstances() {
        Harness h = build();
        StatefulApp.instanceCount.set(0);

        VoidCoreSession a = sessionWithId("session-A");
        VoidCoreSession b = sessionWithId("session-B");

        // Each push mints a fresh instance via the ObjectProvider.
        h.router.push(a, Phase.MENU);
        h.router.push(b, Phase.MENU);

        StatefulApp aTop = (StatefulApp) h.navState.peekFrame(a).screen();
        StatefulApp bTop = (StatefulApp) h.navState.peekFrame(b).screen();

        assertThat(aTop).isNotNull();
        assertThat(bTop).isNotNull();
        assertThat(aTop).isNotSameAs(bTop);
    }

    @Test
    void eventsToOneSessionDoNotMutateTheOthersState() {
        Harness h = build();
        StatefulApp.instanceCount.set(0);

        VoidCoreSession a = sessionWithId("session-A");
        VoidCoreSession b = sessionWithId("session-B");
        h.router.push(a, Phase.MENU);
        h.router.push(b, Phase.MENU);

        StatefulApp aTop = (StatefulApp) h.navState.peekFrame(a).screen();
        StatefulApp bTop = (StatefulApp) h.navState.peekFrame(b).screen();

        // Dispatch 3 events to A; B should see zero.
        h.router.onAppEvent(a, new AppEvent.FieldCommit("x", "1"));
        h.router.onAppEvent(a, new AppEvent.FieldCommit("x", "2"));
        h.router.onAppEvent(a, new AppEvent.FieldCommit("x", "3"));

        assertThat(aTop.eventsReceived).isEqualTo(3);
        assertThat(bTop.eventsReceived).isZero();

        // Dispatch 1 event to B; A unchanged.
        h.router.onAppEvent(b, new AppEvent.FieldCommit("x", "b1"));

        assertThat(aTop.eventsReceived).isEqualTo(3);
        assertThat(bTop.eventsReceived).isEqualTo(1);
    }

    @Test
    void popDiscardsTheInstanceSoNextPushMintsAnewOne() {
        Harness h = build();
        StatefulApp.instanceCount.set(0);

        VoidCoreSession a = sessionWithId("session-A");
        // Seed a root layer so pop()'s root-guard doesn't trigger logout.
        h.navState.resetFrame(a, new io.aeyer.voidcore.ws.flow.screen.Frame(Phase.MENU, new StatefulApp()));

        h.router.push(a, Phase.MENU);
        StatefulApp first = (StatefulApp) h.navState.peekFrame(a).screen();
        h.router.pop(a);
        h.router.push(a, Phase.MENU);
        StatefulApp second = (StatefulApp) h.navState.peekFrame(a).screen();

        assertThat(second).isNotSameAs(first);
        assertThat(second.eventsReceived).isZero();
    }

    @Test
    void singleSessionThatPushesSamePhaseTwiceGetsTwoIndependentFrames() {
        // X11-app analogy: two windows of the same app type are
        // independent instances. Pushing DOCUMENT_SCREEN → DOCUMENT_SCREEN
        // (e.g. drilling into a doc-link from inside another doc) should
        // give two separate instances on the stack.
        Harness h = build();
        StatefulApp.instanceCount.set(0);

        VoidCoreSession a = sessionWithId("session-A");
        h.router.push(a, Phase.MENU);
        StatefulApp inner = (StatefulApp) h.navState.peekFrame(a).screen();
        h.router.push(a, Phase.MENU);
        StatefulApp outer = (StatefulApp) h.navState.peekFrame(a).screen();

        assertThat(outer).isNotSameAs(inner);
        // pop returns to inner (the same instance, with its own state).
        h.router.pop(a);
        StatefulApp afterPop = (StatefulApp) h.navState.peekFrame(a).screen();
        assertThat(afterPop).isSameAs(inner);
    }

    @Test
    void appEventDispatchSurvivesTwoConcurrentSessionsRouting() {
        // Belt-and-braces — round-trip a small dispatch sequence and
        // confirm no interaction between A's and B's frames.
        Harness h = build();
        StatefulApp.instanceCount.set(0);

        VoidCoreSession a = sessionWithId("session-A");
        VoidCoreSession b = sessionWithId("session-B");
        h.router.push(a, Phase.MENU);
        h.router.push(b, Phase.MENU);

        Map<String, StatefulApp> tops = new HashMap<>();
        tops.put("a", (StatefulApp) h.navState.peekFrame(a).screen());
        tops.put("b", (StatefulApp) h.navState.peekFrame(b).screen());

        // Interleave events across both sessions.
        VoidCoreSession[] order = { a, b, a, a, b, a, b, b, b };
        assertThatNoException().isThrownBy(() -> {
            for (VoidCoreSession s : order) {
                h.router.onAppEvent(s, new AppEvent.FocusMove("x", "next"));
            }
        });

        // 4 of the 9 events were on A, 5 on B.
        assertThat(tops.get("a").eventsReceived).isEqualTo(4);
        assertThat(tops.get("b").eventsReceived).isEqualTo(5);
    }
}
