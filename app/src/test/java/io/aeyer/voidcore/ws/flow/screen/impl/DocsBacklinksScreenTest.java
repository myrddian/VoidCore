package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRow;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link DocsBacklinksScreen}. Covers:
 * defensive bounces (no current id, doc gone), render with rows
 * vs empty state, numbered open, and the topics subscription.
 */
class DocsBacklinksScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocsBacklinksScreen screen;
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
        screen = new DocsBacklinksScreen(users);
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

    private DocumentRow doc(long id, String slug) {
        return new DocumentRow(id, slug, slug, DocumentKind.NOTE, "body",
                JSON.createObjectNode(), List.of(),
                100, Visibility.PUBLIC, Status.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void onEnterPopsIfNoCurrentDocumentId() {
        when(session.currentDocumentId()).thenReturn(null);

        screen.onEnter(ctx);

        verify(navigator).pop(session);
    }

    @Test
    void onEnterPopsIfDocGone() {
        when(session.currentDocumentId()).thenReturn(42L);
        when(documents.byId(42L)).thenReturn(Optional.empty());

        screen.onEnter(ctx);

        verify(session).setCurrentDocumentId(null);
        verify(navigator).pop(session);
    }

    @Test
    void onEnterRendersEmptyStateWhenNoBacklinks() {
        DocumentRow d = doc(42L, "lonely");
        when(session.currentDocumentId()).thenReturn(42L);
        when(documents.byId(42L)).thenReturn(Optional.of(d));
        when(documents.findBacklinks(anyLong(), any())).thenReturn(List.of());

        screen.onEnter(ctx);

        boolean sawMain = sent.stream().anyMatch(m ->
                m instanceof ServerMessage.RegionUpdate ru
                        && "main".equals(ru.region()));
        assertThat(sawMain).isTrue();
        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        // Only Q is valid when no rows.
        assertThat(prompt.valid_keys()).isEqualTo("Q");
    }

    @Test
    void onEnterRendersNumberedListWhenBacklinksExist() {
        DocumentRow d = doc(42L, "popular");
        when(session.currentDocumentId()).thenReturn(42L);
        when(documents.byId(42L)).thenReturn(Optional.of(d));
        when(documents.findBacklinks(anyLong(), any())).thenReturn(
                List.of(doc(11, "src1"), doc(22, "src2"), doc(33, "src3")));
        when(users.findById(anyLong())).thenReturn(
                Optional.of(new UserRepository.UserRow(100, "SYSOP", "x", false, false)));

        screen.onEnter(ctx);

        ServerMessage.InputPrompt prompt = sent.stream()
                .filter(m -> m instanceof ServerMessage.InputPrompt)
                .map(m -> (ServerMessage.InputPrompt) m)
                .findFirst().orElseThrow();
        assertThat(prompt.valid_keys()).isEqualTo("123Q");
    }

    @Test
    void onKeyQPops() {
        screen.onKey(ctx, "Q");
        verify(navigator).pop(session);
    }

    @Test
    void onKeyNumberedOpensCorrespondingDoc() {
        when(session.currentDocumentId()).thenReturn(42L);
        when(documents.findBacklinks(anyLong(), any())).thenReturn(
                List.of(doc(11, "src1"), doc(22, "src2")));

        screen.onKey(ctx, "2");

        verify(session).setCurrentDocumentId(22L);
        verify(navigator).push(session, Phase.DOCUMENT_SCREEN);
    }

    @Test
    void onKeyOutOfRangeNumberIgnored() {
        when(session.currentDocumentId()).thenReturn(42L);
        when(documents.findBacklinks(anyLong(), any())).thenReturn(
                List.of(doc(11, "only")));

        screen.onKey(ctx, "5");

        verify(session, never()).setCurrentDocumentId(anyLong());
        verify(navigator, never()).push(any(), any());
    }

    @Test
    void topicsReturnsDocumentsTopic() {
        assertThat(screen.topics(ctx)).containsExactly(DocumentView.TOPIC, InstanceFeatureService.TOPIC);
    }
}
