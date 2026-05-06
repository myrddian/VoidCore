package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.FacetCount;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
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
 * Mockito unit tests for {@link DocsHubScreen}. Verifies render
 * (region.update + keystroke prompt with the right valid_keys set),
 * facet picker push dispatch, [N] / [/] coming-soon notifies,
 * numbered open of recent docs, and the filter-reset on enter.
 */
class DocsHubScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocsHubScreen screen;
    VoidCoreSession session;
    Navigator navigator;
    BbsServices services;
    DocumentView documents;
    DocumentRepository docs;
    MessageBus bus;
    UserRepository users;
    BbsContext ctx;
    List<ServerMessage> sent;

    @BeforeEach
    void setUp() throws IOException {
        users = mock(UserRepository.class);
        docs = mock(DocumentRepository.class);
        screen = new DocsHubScreen(users, docs);
        session = mock(VoidCoreSession.class);
        navigator = mock(Navigator.class);
        services = mock(BbsServices.class);
        documents = mock(DocumentView.class);
        bus = mock(MessageBus.class);
        when(services.documents()).thenReturn(documents);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.INFO_DOCS)).thenReturn(true);
        when(services.bus()).thenReturn(bus);
        sent = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).send(any(ServerMessage.class));
        ctx = new BbsContext(session, null, navigator, services, null);

        // Default: empty pool — every test overrides as needed.
        when(documents.countByFilter(any(), any())).thenReturn(0L);
        when(documents.findByFilter(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(documents.kindFacetCounts(any(), any())).thenReturn(Map.of());
        when(documents.tagFacetCounts(any(), any(), anyInt())).thenReturn(List.of());
        when(documents.authorFacetCounts(any(), any(), anyInt())).thenReturn(List.of());
        when(documents.whenFacetCounts(any(), any())).thenReturn(List.of());
    }

    private DocumentRow doc(long id, String slug, long authorId) {
        return new DocumentRow(id, slug, slug, DocumentKind.NOTE, "body",
                JSON.createObjectNode(), List.of(),
                authorId, Visibility.PUBLIC, Status.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void onEnterClearsFilterAndPage() {
        screen.onEnter(ctx);

        verify(session).setDocsFilter(null);
        verify(session).setDocsResultsPage(null);
    }

    @Test
    void onEnterPersistsCurrentScreenAsKindDocsHub() {
        screen.onEnter(ctx);

        verify(services).persistCurrentScreen(eq(session),
                org.mockito.ArgumentMatchers.contains("\"kind\":\"docs_hub\""));
    }

    @Test
    void onEnterEmptyPoolStillSendsRender() {
        screen.onEnter(ctx);

        boolean sawMain = sent.stream().anyMatch(m ->
                m instanceof ServerMessage.RegionUpdate ru
                        && "main".equals(ru.region()));
        assertThat(sawMain).isTrue();
        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        assertThat(prompt.mode()).isEqualTo("keystroke");
        // No numbered options when pool empty; facet keys + N/Q/slash.
        assertThat(prompt.valid_keys()).contains("KTBWNQ/");
    }

    @Test
    void onEnterWithRecentDocsIncludesNumberedKeys() {
        when(documents.countByFilter(any(), any())).thenReturn(3L);
        when(documents.findByFilter(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(doc(1, "a", 100), doc(2, "b", 100), doc(3, "c", 100)));
        when(users.findById(anyLong())).thenReturn(
                Optional.of(new UserRepository.UserRow(100, "SYSOP", "x", false, false)));

        screen.onEnter(ctx);

        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        // 3 numbered open keys + the facet/menu keys.
        assertThat(prompt.valid_keys()).contains("123KTBWNQ/");
    }

    @Test
    void onKeyKPushesKindFacet() {
        screen.onKey(ctx, "K");
        verify(navigator).push(session, Phase.DOCS_FACET_KIND);
    }

    @Test
    void onKeyTPushesTagFacet() {
        screen.onKey(ctx, "T");
        verify(navigator).push(session, Phase.DOCS_FACET_TAG);
    }

    @Test
    void onKeyBPushesByFacet() {
        screen.onKey(ctx, "B");
        verify(navigator).push(session, Phase.DOCS_FACET_BY);
    }

    @Test
    void onKeyWPushesWhenFacet() {
        screen.onKey(ctx, "W");
        verify(navigator).push(session, Phase.DOCS_FACET_WHEN);
    }

    @Test
    void onKeyQPops() {
        screen.onKey(ctx, "Q");
        verify(navigator).pop(session);
    }

    @Test
    void onKeyNCreatesNewDocAndPushesDocumentScreen() {
        when(session.userId()).thenReturn(7L);
        when(docs.insert(any(), eq("Untitled"), eq(DocumentKind.ARTICLE),
                         eq(""), any(), any(), eq(7L),
                         eq(Visibility.PUBLIC), eq(Status.DRAFT))).thenReturn(42L);
        when(services.json()).thenReturn(JSON);

        screen.onKey(ctx, "N");

        verify(docs).insert(any(), eq("Untitled"), eq(DocumentKind.ARTICLE),
                           eq(""), any(), eq(List.of()), eq(7L),
                           eq(Visibility.PUBLIC), eq(Status.DRAFT));
        verify(session).setCurrentDocumentId(42L);
        verify(navigator).push(session, Phase.DOCUMENT_SCREEN);
    }

    @Test
    void onKeyNWithoutUserSendsWarnNotify() {
        when(session.userId()).thenReturn(null);

        screen.onKey(ctx, "N");

        verify(docs, never()).insert(any(), any(), any(), any(), any(), any(),
                                     anyLong(), any(), any());
        verify(navigator, never()).push(eq(session), any(Phase.class));
        assertThat(sent).anyMatch(m ->
            m instanceof ServerMessage.RegionNotify rn
            && "notifications".equals(rn.region()));
    }

    @Test
    void onKeySlashPushesSearchPrompt() {
        screen.onKey(ctx, "/");
        verify(navigator).push(session, Phase.DOCS_SEARCH_PROMPT);
    }

    @Test
    void onKeyNumberedOpensCorrespondingDoc() {
        when(documents.findByFilter(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(doc(11, "a", 100), doc(22, "b", 100)));

        screen.onKey(ctx, "2");

        verify(session).setCurrentDocumentId(22L);
        verify(navigator).push(session, Phase.DOCUMENT_SCREEN);
    }

    @Test
    void onKeyOutOfRangeNumberIgnored() {
        when(documents.findByFilter(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(doc(11, "a", 100)));

        screen.onKey(ctx, "5"); // only 1 doc

        verify(session, never()).setCurrentDocumentId(anyLong());
        verify(navigator, never()).push(any(), eq(Phase.DOCUMENT_SCREEN));
    }

    @Test
    void topicsReturnsDocumentsTopic() {
        assertThat(screen.topics(ctx)).containsExactly(DocumentView.TOPIC, InstanceFeatureService.TOPIC);
    }
}
