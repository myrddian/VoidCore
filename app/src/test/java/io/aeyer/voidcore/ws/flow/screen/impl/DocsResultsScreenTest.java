package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.FacetCount;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.screen.Navigator;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link DocsResultsScreen}. Covers:
 * <ul>
 *   <li>render with breadcrumb / numbered list / narrow-further</li>
 *   <li>numbered open pushes DOCUMENT_VIEW</li>
 *   <li>narrow-further keys push the matching picker</li>
 *   <li>{@code [..]} drops the most-recently-added facet</li>
 *   <li>{@code [Q]} clears filter state and pops</li>
 *   <li>{@code [J]/[K]} advance / retreat page</li>
 *   <li>empty filter on enter routes back to hub</li>
 * </ul>
 */
class DocsResultsScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocsResultsScreen screen;
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
        screen = new DocsResultsScreen(users);
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

        when(documents.countByFilter(any(), any())).thenReturn(0L);
        when(documents.findByFilter(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(documents.kindFacetCounts(any(), any())).thenReturn(Map.of());
        when(documents.tagFacetCounts(any(), any(), anyInt())).thenReturn(List.of());
        when(documents.authorFacetCounts(any(), any(), anyInt())).thenReturn(List.of());
        when(documents.whenFacetCounts(any(), any())).thenReturn(List.of());
    }

    private DocumentRow doc(long id, String slug) {
        return new DocumentRow(id, slug, slug, DocumentKind.NOTE, "body",
                JSON.createObjectNode(), List.of(),
                100, Visibility.PUBLIC, Status.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void onEnterEmptyFilterRoutesToHub() {
        when(session.docsFilter()).thenReturn(null);

        screen.onEnter(ctx);

        verify(navigator).replaceTopAndEnter(session, Phase.DOCS_HUB);
    }

    @Test
    void onEnterRendersBreadcrumbAndDocs() {
        when(session.docsFilter()).thenReturn("kind=note");
        when(documents.countByFilter(any(), any())).thenReturn(2L);
        when(documents.findByFilter(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(doc(1, "a"), doc(2, "b")));
        when(users.findById(anyLong())).thenReturn(
                Optional.of(new UserRepository.UserRow(100, "SYSOP", "x", false, false)));

        screen.onEnter(ctx);

        boolean sawMain = sent.stream().anyMatch(m ->
                m instanceof ServerMessage.RegionUpdate ru
                        && "main".equals(ru.region()));
        assertThat(sawMain).isTrue();
    }

    @Test
    void onKeyNumberPushesDocumentView() {
        when(session.docsFilter()).thenReturn("kind=note");
        when(session.docsResultsPage()).thenReturn(0);
        when(documents.findByFilter(any(), any(), eq(0), anyInt()))
                .thenReturn(List.of(doc(11, "a"), doc(22, "b")));

        screen.onKey(ctx, "2");

        verify(session).setCurrentDocumentId(22L);
        verify(navigator).push(session, Phase.DOCUMENT_SCREEN);
    }

    @Test
    void onKeyTPushesTagFacet() {
        when(session.docsFilter()).thenReturn("kind=note");
        screen.onKey(ctx, "T");
        verify(navigator).push(session, Phase.DOCS_FACET_TAG);
    }

    @Test
    void onKeyBPushesByFacet() {
        when(session.docsFilter()).thenReturn("kind=note");
        screen.onKey(ctx, "B");
        verify(navigator).push(session, Phase.DOCS_FACET_BY);
    }

    @Test
    void onKeyWPushesWhenFacet() {
        when(session.docsFilter()).thenReturn("kind=note");
        screen.onKey(ctx, "W");
        verify(navigator).push(session, Phase.DOCS_FACET_WHEN);
    }

    @Test
    void onKeyQClearsFilterAndPops() {
        when(session.docsFilter()).thenReturn("kind=note&tag=samples");

        screen.onKey(ctx, "Q");

        verify(session).setDocsFilter(null);
        verify(session).setDocsResultsPage(null);
        verify(navigator).pop(session);
    }

    @Test
    void onKeyDotDropsLastFacetTag() {
        // Filter has kind+tag — dropping the most recent (tag) leaves
        // kind alone and re-enters.
        when(session.docsFilter()).thenReturn("kind=note&tag=samples");
        when(documents.countByFilter(any(), any())).thenReturn(0L);

        screen.onKey(ctx, ".");

        // Tag dropped → filter becomes "kind=note", written back.
        verify(session).setDocsFilter(eq("kind=note"));
        verify(session).setDocsResultsPage(0);
    }

    @Test
    void onKeyDotPopsToHubWhenFilterEmpties() {
        // Single facet — dropping it routes to hub.
        when(session.docsFilter()).thenReturn("kind=note");

        screen.onKey(ctx, ".");

        verify(session).setDocsFilter(null);
        verify(session).setDocsResultsPage(null);
        verify(navigator).replaceTopAndEnter(session, Phase.DOCS_HUB);
    }

    @Test
    void onKeyJAdvancesPage() {
        when(session.docsFilter()).thenReturn("kind=note");
        when(session.docsResultsPage()).thenReturn(0);

        screen.onKey(ctx, "J");

        verify(session).setDocsResultsPage(1);
    }

    @Test
    void onKeyKDoesNotGoBelowZero() {
        when(session.docsFilter()).thenReturn("kind=note");
        when(session.docsResultsPage()).thenReturn(0);

        screen.onKey(ctx, "K");

        verify(session).setDocsResultsPage(0);
    }

    @Test
    void topicsReturnsDocumentsTopic() {
        assertThat(screen.topics(ctx)).containsExactly(DocumentView.TOPIC, InstanceFeatureService.TOPIC);
    }

    // === PR-6: search prompt + sort cycle ====================================

    @Test
    void onKeySlashPushesSearchPrompt() {
        when(session.docsFilter()).thenReturn("kind=note");
        screen.onKey(ctx, "/");
        verify(navigator).push(session, Phase.DOCS_SEARCH_PROMPT);
    }

    @Test
    void onKeySCyclesSortRecentToCreated() {
        when(session.docsFilter()).thenReturn("kind=note");
        when(session.docsResultsSort()).thenReturn("recent");

        screen.onKey(ctx, "S");

        verify(session).setDocsResultsSort("created");
        // Page reset on sort change.
        verify(session).setDocsResultsPage(0);
    }

    @Test
    void onKeySCyclesSortMostLinkedToRecent() {
        when(session.docsFilter()).thenReturn("kind=note");
        when(session.docsResultsSort()).thenReturn("most-linked");

        screen.onKey(ctx, "S");

        verify(session).setDocsResultsSort("recent");
    }

    @Test
    void onKeySNullSortDefaultsToCreated() {
        // Null docsResultsSort → parse returns RECENT → cycle → CREATED.
        when(session.docsFilter()).thenReturn("kind=note");
        when(session.docsResultsSort()).thenReturn(null);

        screen.onKey(ctx, "S");

        verify(session).setDocsResultsSort("created");
    }
}
