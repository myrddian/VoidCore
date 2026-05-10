package io.aeyer.voidcore.ws.flow.screen.impl;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link DocsFacetKindScreen}. Representative
 * of the four picker screens — same shape (numbered selection,
 * filter mutation, replaceTopAndEnter to results, dot-back, Q-back-
 * to-menu, self-pop on empty / single-value).
 */
class DocsFacetKindScreenTest {

    DocsFacetKindScreen screen;
    VoidCoreSession session;
    Navigator navigator;
    BbsServices services;
    DocumentView documents;
    BbsContext ctx;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() throws IOException {
        screen = new DocsFacetKindScreen();
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
        when(session.docsFilter()).thenReturn(null); // empty filter
    }

    @Test
    void onEnterEmptyCountsPops() {
        when(documents.kindFacetCounts(any(), any())).thenReturn(Map.of());

        screen.onEnter(ctx);

        verify(navigator).pop(session);
    }

    @Test
    void onEnterRendersAllVisibleKinds() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("release", 47L);
        counts.put("article", 12L);
        counts.put("note", 166L);
        when(documents.kindFacetCounts(any(), any())).thenReturn(counts);

        screen.onEnter(ctx);

        boolean sawMain = sent.stream().anyMatch(m ->
                m instanceof ServerMessage.RegionUpdate ru
                        && "main".equals(ru.region()));
        assertThat(sawMain).isTrue();

        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        // 3 visible kinds → 1, 2, 3 plus dot and Q.
        assertThat(prompt.valid_keys()).isEqualTo("123.Q");
    }

    @Test
    void onKeyDotPops() {
        screen.onKey(ctx, ".");
        verify(navigator).pop(session);
    }

    @Test
    void onKeyQPops() {
        screen.onKey(ctx, "Q");
        verify(navigator).pop(session);
    }

    @Test
    void onKeyNumberPicksKindAndReplaceTopAndEntersResults() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("release", 1L);
        counts.put("article", 2L);
        when(documents.kindFacetCounts(any(), any())).thenReturn(counts);

        // Built-in order renders article before overlay/compatibility kinds.
        screen.onKey(ctx, "1");

        verify(session).setDocsFilter(eq("kind=article"));
        verify(session).setDocsResultsPage(0);
        verify(navigator).replaceTopAndEnter(session, Phase.DOCS_RESULTS);
    }

    @Test
    void onKeyOutOfRangeNumberIgnored() {
        Map<String, Long> counts = Map.of("note", 1L);
        when(documents.kindFacetCounts(any(), any())).thenReturn(counts);

        screen.onKey(ctx, "5"); // only 1 visible

        verify(navigator, org.mockito.Mockito.never())
                .replaceTopAndEnter(any(), any());
    }

    @Test
    void topicsReturnsDocumentsTopic() {
        assertThat(screen.topics(ctx)).containsExactly(DocumentView.TOPIC, InstanceFeatureService.TOPIC);
    }
}
