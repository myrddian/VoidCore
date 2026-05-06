package io.aeyer.voidcore.ws.flow.screen.impl;

import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.instance.InstanceFeature;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.BbsServices;
import io.aeyer.voidcore.ws.flow.ui.AppEvent;
import io.aeyer.voidcore.ws.flow.ui.AppStateRepository;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentScreenTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository docs;
    AppStateRepository appStateRepo;
    BbsContext ctx;
    BbsServices services;
    VoidCoreSession session;
    DocumentView documentView;
    DocumentScreen screen;
    DocumentRow doc;

    @BeforeEach
    void setUp() {
        docs = mock(DocumentRepository.class);
        appStateRepo = mock(AppStateRepository.class);
        ctx = mock(BbsContext.class);
        services = mock(BbsServices.class);
        session = mock(VoidCoreSession.class);
        documentView = mock(DocumentView.class);

        // Build a sysop-authored public release doc (13-field record).
        doc = new DocumentRow(
                42L, "pattern9", "Pattern Nine",
                DocumentKind.RELEASE,
                "first body",
                null,                        // frontmatter (JsonNode)
                List.of("release"),
                7L,                          // authorId
                Visibility.PUBLIC,
                Status.PUBLISHED,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null                         // anchorDocumentId (UUID)
        );

        when(ctx.session()).thenReturn(session);
        when(ctx.services()).thenReturn(services);
        InstanceFeatureService features = mock(InstanceFeatureService.class);
        when(services.instanceFeatures()).thenReturn(features);
        when(features.enabled(InstanceFeature.INFO_DOCS)).thenReturn(true);
        when(session.currentDocumentId()).thenReturn(42L);
        when(session.sessionToken()).thenReturn("T-7");
        when(services.documents()).thenReturn(documentView);
        when(services.json()).thenReturn(JSON);
        when(documentView.canEdit(any(), eq(doc))).thenReturn(true);
        when(docs.findById(42L)).thenReturn(Optional.of(doc));
        when(appStateRepo.read(anyString(), anyString())).thenReturn(Optional.empty());

        screen = new DocumentScreen(docs, appStateRepo);
    }

    @Test
    void editorCommitSavePersistsBodyAndPublishes() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCommit(
                "body", "new body content", "save"));
        verify(docs).updateBody(42L, "new body content");
        verify(ctx).publish(DocumentView.TOPIC);
        verify(appStateRepo).wipe("T-7", "doc:42");
        verify(ctx, never()).pop();    // save (not save_quit) stays on the screen
    }

    @Test
    void editorCommitSaveQuitPersistsAndPops() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCommit(
                "body", "x", "save_quit"));
        verify(docs).updateBody(42L, "x");
        verify(ctx).pop();
    }

    @Test
    void editorCancelDirtyEmitsWarnAndDoesNotPop() {
        when(appStateRepo.read("T-7", "doc:42")).thenReturn(
                Optional.of(JSON.createObjectNode()
                        .put("body_snapshot", "in-progress")
                        .put("snapshot_at", OffsetDateTime.now().plusSeconds(60).toString())));
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCancel("body", false));
        // dirty path: never pops on plain :q
        verify(ctx, never()).pop();
    }

    @Test
    void editorCancelForcePopsRegardlessOfDirty() {
        when(appStateRepo.read("T-7", "doc:42")).thenReturn(
                Optional.of(JSON.createObjectNode()
                        .put("body_snapshot", "in-progress")
                        .put("snapshot_at", OffsetDateTime.now().plusSeconds(60).toString())));
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorCancel("body", true));
        verify(appStateRepo).wipe("T-7", "doc:42");
        verify(ctx).pop();
    }

    @Test
    void fieldCommitTitleNonEmptyTrimsAndPersists() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("title", "  New Title  "));
        verify(docs).updateTitle(42L, "New Title");
    }

    @Test
    void fieldCommitTitleEmptyEmitsAlertAndDoesNotUpdate() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("title", "  "));
        verify(docs, never()).updateTitle(anyLong(), anyString());
    }

    @Test
    void fieldCommitTagsSplitsOnCommaLowercasesDedupes() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.FieldCommit("tags",
                "Release,  Modulus,  modulus , word tag,  "));
        // expected: [release, modulus, word tag] — comma-separated, lowercase, deduped,
        // trimmed, empty segments dropped, spaces preserved within a tag value
        verify(docs).updateTags(eq(42L), eq(List.of("release", "modulus", "word tag")));
    }

    @Test
    void editorSnapshotWritesToAppState() {
        screen.onEnter(ctx);
        screen.onAppEvent(ctx, new AppEvent.EditorSnapshot("body", "draft body"));
        verify(appStateRepo).write(eq("T-7"), eq("doc:42"), any());
    }
}
