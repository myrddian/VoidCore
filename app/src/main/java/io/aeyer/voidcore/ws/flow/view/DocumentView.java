package io.aeyer.voidcore.ws.flow.view;

import io.aeyer.voidcore.acl.AclPermission;
import io.aeyer.voidcore.acl.AclResourceType;
import io.aeyer.voidcore.acl.AclService;
import io.aeyer.voidcore.documents.DocumentFilter;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.documents.DocumentRow;
import io.aeyer.voidcore.documents.DocumentSort;
import io.aeyer.voidcore.documents.FacetCount;
import io.aeyer.voidcore.documents.Visibility;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cached read-side view of the documents pool per ADR-029. Sits
 * between {@link DocumentRepository} (stateless jOOQ adapter) and
 * the {@link io.aeyer.voidcore.ws.flow.screen.Screen Screen} layer.
 *
 * <p>Uses the same ADR-029 singleton-cache pattern as the other live
 * views: cache is a by-product of the {@link MessageBus}, screens never
 * poke the cache directly. Subscribes to {@link #TOPIC} on
 * {@link PostConstruct}; any writer publishing {@code "documents"}
 * drops the cache. Next read repopulates.
 *
 * <p>Visibility filtering goes through {@link #canRead}. v1 rules:
 * public docs → readable by everyone; private docs → author or
 * sysop or doc-editor only. Pre-auth sessions can read public docs
 * but no private ones (they have no userId to match on).
 *
 * <p>Future {@code PermissionResolver} replaces {@link #canRead} —
 * routing every visibility check through this single funnel keeps
 * the upgrade mechanical.
 *
 * <p>Conditional on {@link DocumentRepository} so the bean drops
 * out cleanly in DB-less test profiles, mirroring sibling Views.
 */
@Component
@ConditionalOnBean(DocumentRepository.class)
public class DocumentView {

    private static final Logger log = LoggerFactory.getLogger(DocumentView.class);

    /** Stable subscription key — not a session id. */
    private static final String SUBSCRIPTION_KEY = "view:documents";

    /** Bus topic this view watches. Writers publish the same string. */
    public static final String TOPIC = "documents";

    private final DocumentRepository repo;
    private final UserRepository users;
    private final AclService acl;
    private final MessageBus bus;

    /**
     * Cached snapshot of {@link DocumentRepository#listAll}. Same
     * volatile-read-once pattern as the sibling Views.
     */
    private volatile List<DocumentRow> cached;

    public DocumentView(DocumentRepository repo, UserRepository users, AclService acl, MessageBus bus) {
        this.repo = repo;
        this.users = users;
        this.acl = acl;
        this.bus = bus;
    }

    @PostConstruct
    void subscribe() {
        bus.subscribe(SUBSCRIPTION_KEY, TOPIC, this::invalidate);
        log.debug("DocumentView subscribed to topic={}", TOPIC);
    }

    @PreDestroy
    void unsubscribe() {
        bus.unsubscribeAll(SUBSCRIPTION_KEY);
    }

    /** Full document pool, ordered updated_at DESC. Cached. */
    public List<DocumentRow> list() {
        List<DocumentRow> snapshot = cached;
        if (snapshot != null) return snapshot;
        snapshot = repo.listAll();
        cached = snapshot;
        return snapshot;
    }

    /**
     * Document by id. Hits the cached list first (covers the
     * common case); falls back to the repo for older / unlisted
     * ids.
     */
    public Optional<DocumentRow> byId(long id) {
        for (DocumentRow d : list()) {
            if (d.id() == id) return Optional.of(d);
        }
        return repo.findById(id);
    }

    /** Document by slug. Cache-first, repo fallback. */
    public Optional<DocumentRow> bySlug(String slug) {
        if (slug == null) return Optional.empty();
        for (DocumentRow d : list()) {
            if (slug.equals(d.slug())) return Optional.of(d);
        }
        return repo.findBySlug(slug);
    }

    /**
     * Slug-or-frontmatter-filename match for the legacy file-area
     * deep-link path. Uncached — uncommon access path; the
     * direct repo query is cheap enough.
     */
    public Optional<DocumentRow> findByFilenameOrSlug(String key) {
        return repo.findByFilenameOrSlug(key);
    }

    /**
     * Transitional helper for the pre-split Releases surface. Resolves a
     * canonical release document by current document id or preserved legacy
     * {@code files.id}.
     */
    public Optional<DocumentRow> byReleaseIdOrLegacyFileId(long id) {
        for (DocumentRow d : list()) {
            if (d.id() == id && "release".equals(d.typeSlug())) return Optional.of(d);
        }
        return repo.findReleaseByIdOrLegacyFileId(id);
    }

    /**
     * Transitional helper for the pre-split Announcements surface. Resolves a
     * canonical article document by current document id or preserved legacy
     * {@code bulletins.id}.
     */
    public Optional<DocumentRow> byArticleIdOrLegacyBulletinId(long id) {
        for (DocumentRow d : list()) {
            if (d.id() == id && "article".equals(d.typeSlug())) return Optional.of(d);
        }
        return repo.findArticleByIdOrLegacyBulletinId(id);
    }

    /**
     * Release-only filename/slug deep-link lookup. Kept separate from the
     * generic helper so router intent resolution doesn't accidentally match a
     * non-release document.
     */
    public Optional<DocumentRow> findReleaseByFilenameOrSlug(String key) {
        return repo.findReleaseByFilenameOrSlug(key);
    }

    /**
     * Visibility / permission funnel. v1 rules:
     * <ul>
     *   <li>{@code doc.visibility == PUBLIC} — readable.</li>
     *   <li>{@code session.userId() == doc.authorId} — author-readable.</li>
     *   <li>{@code session.isSysop()} — sysop-readable.</li>
     *   <li>{@code repo.isEditor(doc.id, session.userId())} — editor-readable.</li>
     * </ul>
     *
     * <p>Every screen / search path that tests visibility goes
     * through here. A future {@code PermissionResolver}
     * replaces this method body without changing call sites.
     */
    public boolean canRead(VoidCoreSession session, DocumentRow doc) {
        if (doc.visibility() == Visibility.PUBLIC) return true;
        Long uid = session.userId();
        if (uid == null) return false;            // pre-auth on private
        if (uid == doc.authorId()) return true;
        if (session.isSysop()) return true;
        if (acl.can(session, AclResourceType.DOCUMENT, doc.id(), AclPermission.VIEW)) return true;
        return repo.isEditor(doc.id(), uid);
    }

    /**
     * Editor / mutation permission funnel — sister to {@link #canRead}.
     * v1 rules:
     * <ul>
     *   <li>Pre-auth → false (no editing without an identity).</li>
     *   <li>Author → true.</li>
     *   <li>Sysop → true.</li>
     *   <li>{@code document_editors} membership → true.</li>
     * </ul>
     *
     * <p>Visibility doesn't matter for editing — a public doc can
     * still be private to write. Same single-funnel discipline as
     * {@code canRead}; future {@code PermissionResolver} replaces
     * both bodies in one place.
     */
    public boolean canEdit(VoidCoreSession session, DocumentRow doc) {
        Long uid = session.userId();
        if (uid == null) return false;
        if (uid == doc.authorId()) return true;
        if (session.isSysop()) return true;
        if (acl.can(session, AclResourceType.DOCUMENT, doc.id(), AclPermission.EDIT)) return true;
        return repo.isEditor(doc.id(), uid);
    }

    /**
     * Did {@code canEdit} succeed for this session purely via the
     * sysop-override branch? A sysop editing a doc they neither
     * authored nor were granted explicit editor on. Used by the
     * editor screens to decide whether to write a {@code sysop_actions}
     * audit row alongside the edit (per PR-4 design §5).
     *
     * <p>Returns false for: pre-auth, the author, non-sysops, and
     * sysops who are also explicit editors (those edits are
     * legitimate, not overrides).
     */
    public boolean isSysopOverride(VoidCoreSession session, DocumentRow doc) {
        if (!session.isSysop()) return false;
        Long uid = session.userId();
        if (uid == null) return false;
        if (uid == doc.authorId()) return false;
        return !repo.isEditor(doc.id(), uid);
    }

    // ─── Faceted-nav read paths (PR-5) ────────────────────────────────────

    /**
     * Filtered, paged list for the faceted-nav surface. Visibility is
     * applied at the SQL layer per {@link #canRead}'s rules; the
     * caller doesn't need to post-filter. Pass-through to the repo —
     * filter queries aren't cached (heterogeneous shape; cache hit
     * rate would be poor).
     */
    public List<DocumentRow> findByFilter(DocumentFilter filter,
                                          VoidCoreSession session,
                                          int offset,
                                          int limit) {
        return findByFilter(filter, session, DocumentSort.RECENT, offset, limit);
    }

    /** PR-6 sort-aware overload. */
    public List<DocumentRow> findByFilter(DocumentFilter filter,
                                          VoidCoreSession session,
                                          DocumentSort sort,
                                          int offset,
                                          int limit) {
        if (sort == null) sort = DocumentSort.RECENT;
        List<DocumentRow> filtered = visibleFiltered(filter, session)
                .sorted(comparatorFor(sort))
                .toList();
        if (offset >= filtered.size()) return List.of();
        int from = Math.max(0, offset);
        int to = Math.min(filtered.size(), from + Math.max(0, limit));
        return filtered.subList(from, to);
    }

    public long countByFilter(DocumentFilter filter, VoidCoreSession session) {
        return visibleFiltered(filter, session).count();
    }

    public Map<String, Long> kindFacetCounts(DocumentFilter filter,
                                             VoidCoreSession session) {
        return visibleFiltered(filter, session)
                .collect(Collectors.groupingBy(
                        DocumentRow::typeSlug,
                        java.util.LinkedHashMap::new,
                        Collectors.counting()));
    }

    public List<FacetCount.Tag> tagFacetCounts(DocumentFilter filter,
                                               VoidCoreSession session,
                                               int topN) {
        return visibleFiltered(filter, session)
                .flatMap(doc -> doc.tags().stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(topN)
                .map(e -> new FacetCount.Tag(e.getKey(), e.getValue()))
                .toList();
    }

    public List<FacetCount.Author> authorFacetCounts(DocumentFilter filter,
                                                     VoidCoreSession session,
                                                     int topN) {
        Map<Long, Long> counts = visibleFiltered(filter, session)
                .collect(Collectors.groupingBy(DocumentRow::authorId, Collectors.counting()));
        return counts.entrySet().stream()
                .map(e -> users.findById(e.getKey())
                        .map(u -> new FacetCount.Author(u.id(), u.handle(), e.getValue())))
                .flatMap(Optional::stream)
                .sorted(Comparator.<FacetCount.Author>comparingLong(FacetCount.Author::count).reversed()
                        .thenComparing(FacetCount.Author::handle))
                .limit(topN)
                .toList();
    }

    public List<FacetCount.Year> whenFacetCounts(DocumentFilter filter,
                                                 VoidCoreSession session) {
        return visibleFiltered(filter, session)
                .map(DocumentRow::updatedAt)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(ts -> ts.getYear(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new FacetCount.Year(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Visibility-aware backlinks (PR-7) — docs that link TO the
     * given target. Pass-through to the repo; the visibility
     * predicate drops sources the session can't read.
     */
    public List<DocumentRow> findBacklinks(long targetId, VoidCoreSession session) {
        return repo.findBacklinksUnfiltered(targetId).stream()
                .filter(doc -> canRead(session, doc))
                .limit(20)
                .toList();
    }

    private Stream<DocumentRow> visibleFiltered(DocumentFilter filter, VoidCoreSession session) {
        return list().stream()
                .filter(doc -> !doc.isDeleted())
                .filter(doc -> canRead(session, doc))
                .filter(doc -> matchesFilter(filter, doc));
    }

    private boolean matchesFilter(DocumentFilter filter, DocumentRow doc) {
        if (filter.kind().isPresent() && !filter.kind().get().equals(doc.typeSlug())) return false;
        if (!doc.tags().containsAll(filter.tagsList())) return false;
        if (!filter.excludedTagsList().isEmpty()
                && doc.tags().stream().anyMatch(filter.excludedTagsList()::contains)) return false;
        if (filter.authorId().isPresent() && filter.authorId().get() != doc.authorId()) return false;
        if (filter.year().isPresent()) {
            OffsetDateTime updated = doc.updatedAt();
            if (updated == null || updated.getYear() != filter.year().get()) return false;
            if (filter.month().isPresent() && updated.getMonthValue() != filter.month().get()) return false;
        }
        if (filter.search().isPresent()) {
            String haystack = (doc.title() + "\n" + doc.body() + "\n" + String.join(" ", doc.tags()))
                    .toLowerCase();
            String[] tokens = filter.search().get().toLowerCase().trim().split("\\s+");
            for (String token : tokens) {
                if (!haystack.contains(token)) return false;
            }
        }
        return true;
    }

    private Comparator<DocumentRow> comparatorFor(DocumentSort sort) {
        Comparator<DocumentRow> recent = Comparator
                .comparing(DocumentRow::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DocumentRow::id, Comparator.reverseOrder());
        return switch (sort) {
            case CREATED -> Comparator
                    .comparing(DocumentRow::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DocumentRow::id, Comparator.reverseOrder());
            case ALPHA -> Comparator
                    .comparing((DocumentRow d) -> d.title() == null ? "" : d.title().toLowerCase())
                    .thenComparing(DocumentRow::id, Comparator.reverseOrder());
            case MOST_LINKED -> {
                Map<Long, Long> counts = repo.incomingLinkCounts(
                        list().stream().map(DocumentRow::id).toList());
                yield Comparator.<DocumentRow>comparingLong(d -> counts.getOrDefault(d.id(), 0L))
                        .reversed()
                        .thenComparing(DocumentRow::id, Comparator.reverseOrder());
            }
            case RECENT -> recent;
        };
    }

    private static long sessionUserIdOr(long fallback, VoidCoreSession session) {
        Long uid = session.userId();
        return uid == null ? fallback : uid;
    }

    /** Drop the cache. Called by the bus on {@link #TOPIC} notify. */
    void invalidate() {
        cached = null;
    }

    /** Visible-for-testing: is the cache currently populated? */
    boolean isCached() {
        return cached != null;
    }
}
