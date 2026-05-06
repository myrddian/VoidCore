package io.aeyer.voidcore.ws.flow.view;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.FacetCount;
import io.aeyer.voidcore.documents.DocumentSort;
import io.aeyer.voidcore.documents.Status;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentView}. Mocks {@link DocumentRepository},
 * {@link MessageBus}, and {@link VoidCoreSession}. Verifies the cache
 * lifecycle (populate → invalidate via bus → repopulate) and the
 * canRead permission funnel across all v1 rule branches.
 */
class DocumentViewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    DocumentRepository repo;
    UserRepository users;
    AclService acl;
    MessageBus bus;
    DocumentView view;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentRepository.class);
        users = mock(UserRepository.class);
        acl = mock(AclService.class);
        bus = mock(MessageBus.class);
        view = new DocumentView(repo, users, acl, bus);
        view.subscribe();   // simulate Spring's @PostConstruct
    }

    private DocumentRow doc(long id, String slug, Visibility vis, long authorId) {
        ObjectNode fm = JSON.createObjectNode();
        return new DocumentRow(id, slug, "Title-" + id, DocumentKind.NOTE,
                "body", fm, List.of(), authorId, vis, Status.PUBLISHED,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    // --- Cache lifecycle -----------------------------------------------------

    @Test
    void firstListCallHitsRepo() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "a", Visibility.PUBLIC, 100)));

        view.list();

        verify(repo, times(1)).listAll();
        assertThat(view.isCached()).isTrue();
    }

    @Test
    void subsequentListCallsHitCache() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "a", Visibility.PUBLIC, 100)));

        view.list();
        view.list();
        view.list();

        verify(repo, times(1)).listAll();
    }

    @Test
    void busNotifyDropsCache() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "a", Visibility.PUBLIC, 100)));
        view.list();
        assertThat(view.isCached()).isTrue();

        view.invalidate();   // simulate bus.notify("documents") → invalidate

        assertThat(view.isCached()).isFalse();
        view.list();
        verify(repo, times(2)).listAll();
    }

    @Test
    void byIdHitsCacheForSeenId() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "a", Visibility.PUBLIC, 100)));

        Optional<DocumentRow> got = view.byId(1);

        assertThat(got).isPresent();
        verify(repo, times(1)).listAll();
        verify(repo, never()).findById(anyLong());
    }

    @Test
    void byIdFallsBackToRepoForUnseenId() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "a", Visibility.PUBLIC, 100)));
        when(repo.findById(99L)).thenReturn(Optional.of(doc(99, "z", Visibility.PUBLIC, 100)));

        Optional<DocumentRow> got = view.byId(99);

        assertThat(got).isPresent();
        verify(repo, times(1)).findById(99L);
    }

    @Test
    void bySlugHitsCacheForSeenSlug() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "alpha", Visibility.PUBLIC, 100)));

        Optional<DocumentRow> got = view.bySlug("alpha");

        assertThat(got).isPresent();
        verify(repo, never()).findBySlug(any());
    }

    @Test
    void bySlugFallsBackToRepoForUnseenSlug() {
        when(repo.listAll()).thenReturn(List.of(doc(1, "alpha", Visibility.PUBLIC, 100)));
        when(repo.findBySlug("beta"))
                .thenReturn(Optional.of(doc(2, "beta", Visibility.PUBLIC, 100)));

        Optional<DocumentRow> got = view.bySlug("beta");

        assertThat(got).isPresent();
        verify(repo, times(1)).findBySlug("beta");
    }

    @Test
    void findByFilenameOrSlugIsUncached() {
        when(repo.findByFilenameOrSlug("PATTERN9.ZIP"))
                .thenReturn(Optional.of(doc(1, "pattern9", Visibility.PUBLIC, 100)));

        view.findByFilenameOrSlug("PATTERN9.ZIP");
        view.findByFilenameOrSlug("PATTERN9.ZIP");

        verify(repo, times(2)).findByFilenameOrSlug("PATTERN9.ZIP");
    }

    // --- canRead permission funnel ------------------------------------------

    private VoidCoreSession sess(Long uid, boolean sysop) {
        VoidCoreSession s = mock(VoidCoreSession.class);
        when(s.userId()).thenReturn(uid);
        when(s.isSysop()).thenReturn(sysop);
        return s;
    }

    @Test
    void canReadPublicAlwaysTrue() {
        DocumentRow pub = doc(1, "p", Visibility.PUBLIC, 100);
        assertThat(view.canRead(sess(null, false), pub)).isTrue();
        assertThat(view.canRead(sess(50L, false), pub)).isTrue();
        assertThat(view.canRead(sess(100L, true), pub)).isTrue();
    }

    @Test
    void canReadPrivateForAuthorTrue() {
        DocumentRow priv = doc(1, "x", Visibility.PRIVATE, 100);
        assertThat(view.canRead(sess(100L, false), priv)).isTrue();
    }

    @Test
    void canReadPrivateForSysopTrue() {
        DocumentRow priv = doc(1, "x", Visibility.PRIVATE, 100);
        assertThat(view.canRead(sess(50L, true), priv)).isTrue();
        verify(repo, never()).isEditor(anyLong(), anyLong());
    }

    @Test
    void canReadPrivateForEditorTrue() {
        DocumentRow priv = doc(1, "x", Visibility.PRIVATE, 100);
        when(repo.isEditor(eq(1L), eq(50L))).thenReturn(true);

        assertThat(view.canRead(sess(50L, false), priv)).isTrue();
        verify(repo, times(1)).isEditor(1L, 50L);
    }

    @Test
    void canReadPrivateForNobodyFalse() {
        DocumentRow priv = doc(1, "x", Visibility.PRIVATE, 100);
        when(repo.isEditor(anyLong(), anyLong())).thenReturn(false);

        assertThat(view.canRead(sess(50L, false), priv)).isFalse();
    }

    @Test
    void canReadPrivateForAclViewerTrue() {
        DocumentRow priv = doc(1, "x", Visibility.PRIVATE, 100);
        VoidCoreSession session = sess(50L, false);
        when(acl.can(session, AclResourceType.DOCUMENT, 1L, AclPermission.VIEW)).thenReturn(true);

        assertThat(view.canRead(session, priv)).isTrue();
        verify(repo, never()).isEditor(anyLong(), anyLong());
    }

    @Test
    void canReadPrivateForPreAuthFalse() {
        DocumentRow priv = doc(1, "x", Visibility.PRIVATE, 100);

        assertThat(view.canRead(sess(null, false), priv)).isFalse();
        verify(repo, never()).isEditor(anyLong(), anyLong());
    }

    @Test
    void canReadPublicForPreAuthTrue() {
        DocumentRow pub = doc(1, "p", Visibility.PUBLIC, 100);

        assertThat(view.canRead(sess(null, false), pub)).isTrue();
    }

    // === canEdit funnel ======================================================

    @Test
    void canEditPreAuthFalse() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        assertThat(view.canEdit(sess(null, false), d)).isFalse();
        verify(repo, never()).isEditor(anyLong(), anyLong());
    }

    @Test
    void canEditAuthorTrue() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        assertThat(view.canEdit(sess(100L, false), d)).isTrue();
    }

    @Test
    void canEditSysopTrue() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        assertThat(view.canEdit(sess(50L, true), d)).isTrue();
        verify(repo, never()).isEditor(anyLong(), anyLong());
    }

    @Test
    void canEditEditorTrue() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        when(repo.isEditor(eq(1L), eq(50L))).thenReturn(true);

        assertThat(view.canEdit(sess(50L, false), d)).isTrue();
    }

    @Test
    void canEditNobodyFalse() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        when(repo.isEditor(anyLong(), anyLong())).thenReturn(false);

        assertThat(view.canEdit(sess(50L, false), d)).isFalse();
    }

    @Test
    void canEditAclEditorTrue() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        VoidCoreSession session = sess(50L, false);
        when(acl.can(session, AclResourceType.DOCUMENT, 1L, AclPermission.EDIT)).thenReturn(true);

        assertThat(view.canEdit(session, d)).isTrue();
        verify(repo, never()).isEditor(anyLong(), anyLong());
    }

    // === isSysopOverride funnel ==============================================

    @Test
    void isSysopOverridePreAuthFalse() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        assertThat(view.isSysopOverride(sess(null, false), d)).isFalse();
    }

    @Test
    void isSysopOverrideNonSysopFalse() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        assertThat(view.isSysopOverride(sess(50L, false), d)).isFalse();
    }

    @Test
    void isSysopOverrideSysopAuthorFalse() {
        // Sysop editing their own doc is not an override.
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 50);
        assertThat(view.isSysopOverride(sess(50L, true), d)).isFalse();
    }

    @Test
    void isSysopOverrideSysopExplicitEditorFalse() {
        // Sysop AND explicit editor — legitimate edit, not an override.
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        when(repo.isEditor(eq(1L), eq(50L))).thenReturn(true);
        assertThat(view.isSysopOverride(sess(50L, true), d)).isFalse();
    }

    @Test
    void isSysopOverrideSysopNotAuthorNotEditorTrue() {
        DocumentRow d = doc(1, "x", Visibility.PUBLIC, 100);
        when(repo.isEditor(eq(1L), eq(50L))).thenReturn(false);
        assertThat(view.isSysopOverride(sess(50L, true), d)).isTrue();
    }

    // === discovery via ACL ==================================================

    @Test
    void findByFilterIncludesAclGrantedPrivateDoc() {
        DocumentRow publicDoc = doc(1, "pub", Visibility.PUBLIC, 100);
        DocumentRow privateDoc = doc(2, "priv", Visibility.PRIVATE, 200);
        VoidCoreSession session = sess(50L, false);
        when(repo.listAll()).thenReturn(List.of(publicDoc, privateDoc));
        when(acl.can(session, AclResourceType.DOCUMENT, 2L, AclPermission.VIEW)).thenReturn(true);

        List<DocumentRow> docs = view.findByFilter(DocumentFilter.empty(), session, DocumentSort.RECENT, 0, 10);

        assertThat(docs).extracting(DocumentRow::id).contains(2L, 1L);
    }

    @Test
    void countAndKindFacetsIncludeAclGrantedPrivateDoc() {
        DocumentRow publicDoc = doc(1, "pub", Visibility.PUBLIC, 100);
        ObjectNode fm = JSON.createObjectNode();
        DocumentRow privateArticle = new DocumentRow(2L, "priv", "Private",
                DocumentKind.ARTICLE, "body", fm, List.of("secret"), 200L,
                Visibility.PRIVATE, Status.PUBLISHED, OffsetDateTime.now(),
                OffsetDateTime.now(), null);
        VoidCoreSession session = sess(50L, false);
        when(repo.listAll()).thenReturn(List.of(publicDoc, privateArticle));
        when(acl.can(session, AclResourceType.DOCUMENT, 2L, AclPermission.VIEW)).thenReturn(true);
        when(users.findById(100L)).thenReturn(Optional.of(new UserRepository.UserRow(100L, "SYSOP", "x", false, false)));
        when(users.findById(200L)).thenReturn(Optional.of(new UserRepository.UserRow(200L, "VOID", "x", false, false)));

        assertThat(view.countByFilter(DocumentFilter.empty(), session)).isEqualTo(2);
        assertThat(view.kindFacetCounts(DocumentFilter.empty(), session)).containsEntry(DocumentKind.ARTICLE, 1L);
        List<FacetCount.Author> authors = view.authorFacetCounts(DocumentFilter.empty(), session, 10);
        assertThat(authors).extracting(FacetCount.Author::handle).contains("VOID");
    }

    @Test
    void findBacklinksFiltersViaCanReadNotLegacyVisibilitySql() {
        DocumentRow privateBacklink = doc(2, "priv", Visibility.PRIVATE, 200);
        VoidCoreSession session = sess(50L, false);
        when(repo.findBacklinksUnfiltered(99L)).thenReturn(List.of(privateBacklink));
        when(acl.can(session, AclResourceType.DOCUMENT, 2L, AclPermission.VIEW)).thenReturn(true);

        List<DocumentRow> backlinks = view.findBacklinks(99L, session);

        assertThat(backlinks).extracting(DocumentRow::id).containsExactly(2L);
    }
}
