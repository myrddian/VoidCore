package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.LoginSummary;
import io.aeyer.voidcore.auth.LoginSummaryService;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link LoginSummaryScreen}. Verifies render
 * (per-content-type rows shown only for non-zero counts), letter
 * shortcuts replace-top to the relevant phase, [Q] pops, empty
 * summary self-pops on enter.
 */
class LoginSummaryScreenTest {

    VoidCoreSession session;
    Navigator navigator;
    BbsServices services;
    LoginSummaryService summaries;
    BbsContext ctx;
    LoginSummaryScreen screen;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() throws IOException {
        summaries = mock(LoginSummaryService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.aeyer.voidcore.atmosphere.FortuneRepository> fp =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(fp.getIfAvailable()).thenReturn(null);
        screen = new LoginSummaryScreen(summaries, fp);
        session = mock(VoidCoreSession.class);
        navigator = mock(Navigator.class);
        services = mock(BbsServices.class);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.ANNOUNCEMENTS)).thenReturn(true);
        when(features.enabled(InstanceFeature.RELEASES)).thenReturn(true);
        when(features.enabled(InstanceFeature.MESSAGE_BOARD)).thenReturn(true);
        when(features.enabled(InstanceFeature.VOIDMAIL)).thenReturn(true);
        when(features.enabled(InstanceFeature.ONELINERS)).thenReturn(true);
        when(features.enabled(InstanceFeature.INFO_DOCS)).thenReturn(true);
        sent = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));
        ctx = new BbsContext(session, null, navigator, services, null);
    }

    @Test
    void onEnterPopsIfNoUserId() {
        when(session.userId()).thenReturn(null);

        screen.onEnter(ctx);

        verify(navigator).pop(session);
    }

    @Test
    void onEnterPopsIfSummaryEmpty() {
        when(session.userId()).thenReturn(42L);
        when(session.previousLastCall()).thenReturn(Instant.now().minusSeconds(3600));
        when(summaries.compute(anyLong(), any())).thenReturn(LoginSummary.empty());

        screen.onEnter(ctx);

        verify(navigator).pop(session);
    }

    @Test
    void onEnterRendersOnlyNonZeroRows() {
        when(session.userId()).thenReturn(42L);
        when(session.previousLastCall()).thenReturn(Instant.now().minusSeconds(86400));
        when(summaries.compute(anyLong(), any()))
                .thenReturn(new LoginSummary(2, 0, 5, 0, 1));

        screen.onEnter(ctx);

        boolean sawMain = sent.stream().anyMatch(m ->
                m instanceof ServerMessage.RegionUpdate ru
                        && "main".equals(ru.region()));
        assertThat(sawMain).isTrue();
        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        // Articles=2 (B), threads=5 (M), netmail=1 (N), Q always.
        assertThat(prompt.valid_keys()).isEqualTo("BMNQ");
    }

    @Test
    void onEnterRendersAllRowsWhenAllNonZero() {
        when(session.userId()).thenReturn(42L);
        when(session.previousLastCall()).thenReturn(Instant.now().minusSeconds(86400));
        when(summaries.compute(anyLong(), any()))
                .thenReturn(new LoginSummary(1, 1, 1, 1, 1));

        screen.onEnter(ctx);

        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        assertThat(prompt.valid_keys()).isEqualTo("BFMNOQ");
    }

    @Test
    void onKeyQPops() {
        screen.onKey(ctx, "Q");
        verify(navigator).pop(session);
        verify(navigator, never()).replaceTopAndEnter(any(), any());
    }

    @Test
    void onKeyBReplaceTopToBulletins() {
        screen.onKey(ctx, "B");
        verify(navigator).replaceTopAndEnter(session, Phase.BULLETINS_LIST);
    }

    @Test
    void onKeyFReplaceTopToFiles() {
        screen.onKey(ctx, "F");
        verify(navigator).replaceTopAndEnter(session, Phase.RELEASES_LIST);
    }

    @Test
    void onKeyMReplaceTopToBases() {
        screen.onKey(ctx, "M");
        verify(navigator).replaceTopAndEnter(session, Phase.BASES_LIST);
    }

    @Test
    void onKeyNReplaceTopToNetmail() {
        screen.onKey(ctx, "N");
        verify(navigator).replaceTopAndEnter(session, Phase.NETMAIL_INBOX);
    }

    @Test
    void onKeyOReplaceTopToOneliners() {
        screen.onKey(ctx, "O");
        verify(navigator).replaceTopAndEnter(session, Phase.ONELINERS);
    }

    @Test
    void onKeyIReplaceTopToDocsHub() {
        screen.onKey(ctx, "I");
        verify(navigator).replaceTopAndEnter(session, Phase.DOCS_HUB);
    }

    @Test
    void onEnterPersistsCurrentScreenAsLoginSummary() {
        when(session.userId()).thenReturn(42L);
        when(session.previousLastCall()).thenReturn(Instant.now().minusSeconds(86400));
        when(summaries.compute(anyLong(), any()))
                .thenReturn(new LoginSummary(2, 0, 0, 0, 0));

        screen.onEnter(ctx);

        verify(services).persistCurrentScreen(any(),
                org.mockito.ArgumentMatchers.contains("\"kind\":\"login_summary\""));
    }
}
