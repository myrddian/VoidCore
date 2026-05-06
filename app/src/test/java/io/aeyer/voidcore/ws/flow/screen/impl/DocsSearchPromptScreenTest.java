package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link DocsSearchPromptScreen}. Verifies
 * the line prompt, parser-driven filter mutation, replaceTopAndEnter
 * to results, empty-cancels, and warning-notify paths.
 */
class DocsSearchPromptScreenTest {

    DocsSearchPromptScreen screen;
    VoidCoreSession session;
    Navigator navigator;
    BbsServices services;
    DocumentView documents;
    UserRepository users;
    BbsContext ctx;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() throws IOException {
        users = mock(UserRepository.class);
        screen = new DocsSearchPromptScreen(users);
        session = mock(VoidCoreSession.class);
        navigator = mock(Navigator.class);
        services = mock(BbsServices.class);
        documents = mock(DocumentView.class);
        when(services.documents()).thenReturn(documents);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.INFO_DOCS)).thenReturn(true);
        sent = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));
        ctx = new BbsContext(session, null, navigator, services, null);
    }

    @Test
    void onEnterSendsLinePrompt() {
        screen.onEnter(ctx);

        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        assertThat(prompt.mode()).isEqualTo("line");
    }

    @Test
    void onLineEmptyCancels() {
        screen.onLine(ctx, "");

        verify(navigator).pop(session);
        verify(navigator, never()).replaceTopAndEnter(any(), any());
    }

    @Test
    void onLineBareWordsAppliedAsSearch() {
        when(session.docsFilter()).thenReturn(null);

        screen.onLine(ctx, "kick drum");

        verify(session).setDocsFilter(eq("search=kick%20drum"));
        verify(session).setDocsResultsPage(0);
        verify(navigator).replaceTopAndEnter(session, Phase.DOCS_RESULTS);
    }

    @Test
    void onLineFacetExpressionAppliedAndIntersectedWithBase() {
        when(session.docsFilter()).thenReturn("kind=howto");

        screen.onLine(ctx, "tag:samples");

        verify(session).setDocsFilter(eq("kind=howto&tag=samples"));
        verify(navigator).replaceTopAndEnter(session, Phase.DOCS_RESULTS);
    }

    @Test
    void onLineNoEffectExpressionSurfacesWarningAndPops() {
        when(session.docsFilter()).thenReturn(null);

        screen.onLine(ctx, "foobar:baz"); // unknown facet → no change

        // Notify surfaced.
        boolean sawNotify = sent.stream()
                .anyMatch(m -> m instanceof ServerMessage.RegionNotify);
        assertThat(sawNotify).isTrue();
        verify(navigator).pop(session);
        verify(navigator, never()).replaceTopAndEnter(any(), any());
    }

    @Test
    void onCancelPops() {
        screen.onCancel(ctx);
        verify(navigator).pop(session);
    }
}
