package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Record3;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.aeyer.voidcore.jooq.Tables.DOCUMENTS;
import static io.aeyer.voidcore.jooq.Tables.DOCUMENT_EDITORS;
import static io.aeyer.voidcore.jooq.Tables.DOCUMENT_LINKS;
import static io.aeyer.voidcore.jooq.Tables.DOCUMENT_REVISIONS;
import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * Read-only access to the {@code documents} table per
 * SPEC-documents.md §2 / ADR-005a. Mutations land with PR-4
 * (the editor); v1 PR-2 is read-side only — every consumer goes
 * through {@link io.aeyer.voidcore.ws.flow.view.DocumentView} for
 * caching + the {@code canRead} permission funnel.
 *
 * <p>Methods don't filter by visibility / status — that's a
 * View-layer concern (the View has session context, the repo
 * doesn't). The repo returns the full pool; the View applies
 * {@code canRead} per call.
 */
public class DocumentRepository {

    private static final String TYPE_ARTICLE = "article";
    private static final String TYPE_RELEASE = "release";
    private static final Field<Boolean> ARTICLE_PINNED = DSL.field(
            "coalesce((frontmatter->>'pinned')::boolean, false)", Boolean.class);
    private static final Field<Long> LEGACY_BULLETIN_ID = DSL.field(
            "(frontmatter->>'legacy_bulletin_id')::bigint", Long.class);
    private static final Field<Long> LEGACY_FILE_ID = DSL.field(
            "(frontmatter->>'legacy_file_id')::bigint", Long.class);

    private final DSLContext dsl;
    private final ObjectMapper json;
    private final SchemaRepository schemaRepo;
    private final FrontmatterValidator validator;

    public DocumentRepository(DSLContext dsl, ObjectMapper json) {
        this(dsl, json, new SchemaRepository(dsl, json), new FrontmatterValidator());
    }

    public DocumentRepository(DSLContext dsl,
                              ObjectMapper json,
                              SchemaRepository schemaRepo,
                              FrontmatterValidator validator) {
        this.dsl = dsl;
        this.json = json;
        this.schemaRepo = schemaRepo;
        this.validator = validator;
    }

    public Optional<DocumentRow> findById(long id) {
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.ID.eq(id))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .fetchOptional()
                .map(this::toRow);
    }

    public Optional<DocumentRow> findByIdIncludingDeleted(long id) {
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.ID.eq(id))
                .fetchOptional()
                .map(this::toRow);
    }

    public Optional<DocumentRow> findBySlug(String slug) {
        if (slug == null) return Optional.empty();
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.SLUG.eq(slug))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .fetchOptional()
                .map(this::toRow);
    }

    /**
     * Look up by either slug match or
     * {@code lower(frontmatter->>'filename') = lower(:key)}.
     * Used by the legacy {@code ?nfo=PATTERN9} deep-link path
     * during the PR-3 file-screen migration. Slug match takes
     * precedence; ties broken by lowest id.
     */
    public Optional<DocumentRow> findByFilenameOrSlug(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String lowered = key.toLowerCase();
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.SLUG.eq(lowered)
                        .or(DSL.field(
                                "lower(frontmatter->>'filename')",
                                String.class).eq(lowered)))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .orderBy(DOCUMENTS.ID.asc())
                .limit(1)
                .fetchOptional()
                .map(this::toRow);
    }

    /**
     * Transitional helper for the pre-split Releases surface. Restricts the
     * legacy filename-or-slug lookup to {@code type_slug='release'} so the
     * screen/router path no longer depends on the old file adapter.
     */
    public Optional<DocumentRow> findReleaseByFilenameOrSlug(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String lowered = key.toLowerCase();
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(TYPE_RELEASE))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .and(DOCUMENTS.SLUG.eq(lowered)
                        .or(DSL.field(
                                "lower(frontmatter->>'filename')",
                                String.class).eq(lowered)))
                .orderBy(DOCUMENTS.ID.asc())
                .limit(1)
                .fetchOptional()
                .map(this::toRow);
    }

    /**
     * Transitional helper for persisted screen state / links that still carry
     * the dropped {@code bulletins.id} values.
     */
    public Optional<DocumentRow> findArticleByIdOrLegacyBulletinId(long id) {
        Optional<DocumentRow> direct = findById(id)
                .filter(doc -> TYPE_ARTICLE.equals(doc.typeSlug()));
        if (direct.isPresent()) return direct;
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(TYPE_ARTICLE))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .and(LEGACY_BULLETIN_ID.eq(id).or(DOCUMENTS.SLUG.eq("bulletin-" + id)))
                .limit(1)
                .fetchOptional()
                .map(this::toRow);
    }

    /**
     * Transitional helper for persisted screen state / links that still carry
     * the dropped {@code files.id} values.
     */
    public Optional<DocumentRow> findReleaseByIdOrLegacyFileId(long id) {
        Optional<DocumentRow> direct = findById(id)
                .filter(doc -> TYPE_RELEASE.equals(doc.typeSlug()));
        if (direct.isPresent()) return direct;
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(TYPE_RELEASE))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .and(LEGACY_FILE_ID.eq(id))
                .limit(1)
                .fetchOptional()
                .map(this::toRow);
    }

    /** Full pool, ordered by updated_at DESC. */
    public List<DocumentRow> listAll() {
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.DELETED_AT.isNull())
                .orderBy(DOCUMENTS.UPDATED_AT.desc())
                .fetch(this::toRow);
    }

    /**
     * Count of docs of a given kind created strictly after {@code since}.
     * Null cutoff counts everything. Used by the login-summary screen
     * (ticket #85) — articles are the v1.5 substrate's "bulletins" and
     * releases are the v1.5 "files".
     */
    public long countByKindSince(DocumentKind kind, OffsetDateTime since) {
        var step = dsl.selectCount()
                .from(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(kind.wireValue()))
                .and(DOCUMENTS.DELETED_AT.isNull());
        if (since == null) return step.fetchOne(0, Long.class);
        return step.and(DOCUMENTS.CREATED_AT.gt(since))
                .fetchOne(0, Long.class);
    }

    /** Narrow by kind, ordered by updated_at DESC. */
    public List<DocumentRow> listByKind(DocumentKind kind) {
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(kind.wireValue()))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .orderBy(DOCUMENTS.UPDATED_AT.desc())
                .fetch(this::toRow);
    }

    /**
     * {@code kind=article AND frontmatter->>'pinned' = 'true'}.
     * Surfaces pinned articles for the main-menu summary panel
     * (replaces the legacy {@code bulletins WHERE pinned=true}
     * once the article screen migrates in PR-3).
     */
    public List<DocumentRow> listPinnedArticles() {
        return dsl.selectFrom(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(DocumentKind.ARTICLE.wireValue()))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .and(DSL.field(
                        "frontmatter->>'pinned'",
                        String.class).eq("true"))
                .orderBy(DOCUMENTS.UPDATED_AT.desc())
                .fetch(this::toRow);
    }

    /**
     * Membership check for {@link io.aeyer.voidcore.ws.flow.view.DocumentView#canRead}
     * and {@link io.aeyer.voidcore.ws.flow.view.DocumentView#canEdit}.
     * Used only on the rare private-doc / non-author edit path; v1 has
     * near-zero private docs and editor rows so this stays cheap.
     */
    public boolean isEditor(long documentId, long userId) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(DOCUMENT_EDITORS)
                        .where(DOCUMENT_EDITORS.DOCUMENT_ID.eq(documentId))
                        .and(DOCUMENT_EDITORS.USER_ID.eq(userId)));
    }

    // === Write paths (v1.5 PR-4) =============================================

    /**
     * Insert a new document. Returns the generated id.
     *
     * <p>Schema-side defaults populate {@code created_at} /
     * {@code updated_at} / {@code search_vector} / {@code status} /
     * {@code visibility} where the supplied row leaves them at their
     * default sentinels. The {@code search_vector} trigger fires
     * automatically on insert.
     *
     * <p>v1 PR-4 doesn't expose a new-doc walk yet; this method
     * exists for completeness so the v1.5 milestone can land
     * without a follow-up migration of the repo surface.
     */
    public long insert(String slug,
                       String title,
                       DocumentKind kind,
                       String body,
                       JsonNode frontmatter,
                       List<String> tags,
                       long authorId,
                       Visibility visibility,
                       Status status) {
        return insertWithTypeSlug(slug, title, kind.wireValue(), body, frontmatter, tags,
                authorId, visibility, status);
    }

    public long insertWithTypeSlug(String slug,
                                   String title,
                                   String typeSlug,
                                   String body,
                                   JsonNode frontmatter,
                                   List<String> tags,
                                   long authorId,
                                   Visibility visibility,
                                   Status status) {
        try {
            Schema schema = schemaRepo.findActive(typeSlug)
                    .orElseThrow(() -> new UnknownTypeException(typeSlug));
            JsonNode effectiveFrontmatter = frontmatter == null
                    ? json.createObjectNode()
                    : frontmatter;
            validator.validate(schema, effectiveFrontmatter);
            String fmText = frontmatter == null
                    ? "{}"
                    : json.writeValueAsString(frontmatter);
            String[] tagsArr = tags == null
                    ? new String[0]
                    : tags.toArray(new String[0]);
            Long id = dsl.insertInto(DOCUMENTS)
                    .set(DOCUMENTS.SLUG, slug)
                    .set(DOCUMENTS.TITLE, title)
                    .set(DOCUMENTS.TYPE_SLUG, schema.slug())
                    .set(DOCUMENTS.TYPE_VERSION, schema.version())
                    .set(DOCUMENTS.REV, 1)
                    .set(DOCUMENTS.BODY, body == null ? "" : body)
                    .set(DOCUMENTS.FRONTMATTER, JSONB.valueOf(fmText))
                    .set(DOCUMENTS.TAGS, tagsArr)
                    .set(DOCUMENTS.AUTHOR_ID, authorId)
                    .set(DOCUMENTS.VISIBILITY, visibility.wireValue())
                    .set(DOCUMENTS.STATUS, status.wireValue())
                    .returningResult(DOCUMENTS.ID)
                    .fetchOne(DOCUMENTS.ID);
            if (id == null) {
                throw new IllegalStateException(
                        "INSERT … RETURNING produced no id");
            }
            // PR-7: rewrite the link graph for this fresh doc.
            // Uses the body that was just inserted; unresolved slugs
            // are silently skipped.
            replaceLinksFor(id, body);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DocumentRepository.insert failed: " + e.getMessage(), e);
        }
    }

    /**
     * Per-field update. {@code updated_at} is bumped to {@code now()}
     * so the View's {@code updated_at DESC} ordering reflects the
     * edit. The {@code search_vector} trigger fires automatically
     * when {@code title}, {@code tags}, or {@code body} changes.
     */
    public void updateTitle(long id, String title) {
        DocumentRow current = findById(id)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + id));
        snapshotPublished(current, current.authorId(), "title updated", false);
        dsl.update(DOCUMENTS)
                .set(DOCUMENTS.TITLE, title)
                .set(DOCUMENTS.REV, nextRevAfterSave(current))
                .set(DOCUMENTS.UPDATED_AT, OffsetDateTime.now())
                .where(DOCUMENTS.ID.eq(id))
                .execute();
    }

    public void updateBody(long id, String body) {
        DocumentRow current = findById(id)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + id));
        snapshotPublished(current, current.authorId(), "body updated", false);
        dsl.update(DOCUMENTS)
                .set(DOCUMENTS.BODY, body == null ? "" : body)
                .set(DOCUMENTS.REV, nextRevAfterSave(current))
                .set(DOCUMENTS.UPDATED_AT, OffsetDateTime.now())
                .where(DOCUMENTS.ID.eq(id))
                .execute();
        // PR-7: rewrite the link graph against the new body. Old
        // rows for this source go away; new ones land. If the new
        // body has no ~slug references, all rows for this source
        // are deleted.
        replaceLinksFor(id, body);
    }

    public void updateTags(long id, List<String> tags) {
        DocumentRow current = findById(id)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + id));
        snapshotPublished(current, current.authorId(), "tags updated", false);
        String[] tagsArr = tags == null
                ? new String[0]
                : tags.toArray(new String[0]);
        dsl.update(DOCUMENTS)
                .set(DOCUMENTS.TAGS, tagsArr)
                .set(DOCUMENTS.REV, nextRevAfterSave(current))
                .set(DOCUMENTS.UPDATED_AT, OffsetDateTime.now())
                .where(DOCUMENTS.ID.eq(id))
                .execute();
    }

    /**
     * PR-4c: set a single key inside the {@code frontmatter} JSONB
     * blob. Atomic — uses Postgres {@code jsonb_set} so there's no
     * read-modify-write race against a concurrent write to a
     * different key. Bumps {@code updated_at}.
     *
     * @param value pre-serialised JSON value (e.g. {@code "\"hello\""},
     *              {@code "42"}, {@code "true"}). Caller is
     *              responsible for emitting valid JSON.
     */
    public void setFrontmatterField(long id, String key, String jsonValue) {
        try {
            DocumentRow current = findById(id)
                    .orElseThrow(() -> new IllegalStateException("Document not found: " + id));
            snapshotPublished(current, current.authorId(), "frontmatter updated", false);
            String path = "{" + key + "}";
            dsl.execute(
                    "UPDATE documents " +
                    "SET frontmatter = jsonb_set(frontmatter, ?::text[], ?::jsonb), " +
                    "    rev = ?, " +
                    "    updated_at = now() " +
                    "WHERE id = ?",
                    path, jsonValue, nextRevAfterSave(current), id);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DocumentRepository.setFrontmatterField failed: " + e.getMessage(), e);
        }
    }

    /** PR-4c: delete a key from {@code frontmatter}. No-op if absent. */
    public void clearFrontmatterField(long id, String key) {
        DocumentRow current = findById(id)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + id));
        snapshotPublished(current, current.authorId(), "frontmatter updated", false);
        dsl.execute(
                "UPDATE documents " +
                "SET frontmatter = frontmatter - ?, rev = ?, updated_at = now() " +
                "WHERE id = ?",
                key, nextRevAfterSave(current), id);
    }

    public void updateFrontmatterText(long id, String key, String value) {
        try {
            if (value == null) {
                clearFrontmatterField(id, key);
            } else {
                setFrontmatterField(id, key, json.writeValueAsString(value));
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DocumentRepository.updateFrontmatterText failed: " + e.getMessage(), e);
        }
    }

    public void updateFrontmatterNumber(long id, String key, Number value) {
        if (value == null) {
            clearFrontmatterField(id, key);
            return;
        }
        setFrontmatterField(id, key, String.valueOf(value));
    }

    public void updateFrontmatterBoolean(long id, String key, boolean value) {
        setFrontmatterField(id, key, value ? "true" : "false");
    }

    public void updateVisibility(long id, Visibility visibility) {
        DocumentRow current = findById(id)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + id));
        snapshotPublished(current, current.authorId(), "visibility updated", false);
        dsl.update(DOCUMENTS)
                .set(DOCUMENTS.VISIBILITY, visibility.wireValue())
                .set(DOCUMENTS.REV, nextRevAfterSave(current))
                .set(DOCUMENTS.UPDATED_AT, OffsetDateTime.now())
                .where(DOCUMENTS.ID.eq(id))
                .execute();
    }

    /**
     * Soft-delete a document. Standard read paths exclude deleted rows.
     */
    public void delete(long id) {
        DocumentRow current = findByIdIncludingDeleted(id).orElse(null);
        if (current == null || current.isDeleted()) return;
        snapshotRevision(current, current.authorId(), "deleted", true);
        dsl.update(DOCUMENTS)
                .set(DOCUMENTS.DELETED_AT, OffsetDateTime.now())
                .set(DOCUMENTS.DELETED_BY, current.authorId())
                .set(DOCUMENTS.REV, current.rev() + 1)
                .set(DOCUMENTS.UPDATED_AT, OffsetDateTime.now())
                .where(DOCUMENTS.ID.eq(id))
                .execute();
        dsl.deleteFrom(DOCUMENT_LINKS)
                .where(DOCUMENT_LINKS.SOURCE_ID.eq(id))
                .execute();
    }

    /**
     * Swap an article's {@code updated_at} with its neighbour inside the same
     * pinned/unpinned bucket. Transitional helper for the announcement ordering
     * UX while the legacy bulletin adapter is being retired.
     */
    public boolean moveArticle(long id, int delta) {
        List<Record3<Long, Boolean, OffsetDateTime>> rows = dsl.select(
                        DOCUMENTS.ID, ARTICLE_PINNED, DOCUMENTS.UPDATED_AT)
                .from(DOCUMENTS)
                .where(DOCUMENTS.TYPE_SLUG.eq(TYPE_ARTICLE))
                .and(DOCUMENTS.STATUS.eq("published"))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .orderBy(ARTICLE_PINNED.desc(), DOCUMENTS.UPDATED_AT.desc(), DOCUMENTS.ID.asc())
                .fetch();

        Long resolvedId = findArticleByIdOrLegacyBulletinId(id)
                .map(DocumentRow::id)
                .orElse(null);
        if (resolvedId == null) return false;

        int idx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get(DOCUMENTS.ID).equals(resolvedId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;

        int otherIdx = idx + delta;
        if (otherIdx < 0 || otherIdx >= rows.size()) return false;

        Record3<Long, Boolean, OffsetDateTime> current = rows.get(idx);
        Record3<Long, Boolean, OffsetDateTime> other = rows.get(otherIdx);
        Boolean currentPinned = current.value2();
        Boolean otherPinned = other.value2();
        if (currentPinned == null || otherPinned == null || !currentPinned.equals(otherPinned)) {
            return false;
        }

        dsl.update(DOCUMENTS)
                .set(DOCUMENTS.UPDATED_AT, DSL.case_()
                        .when(DOCUMENTS.ID.eq(current.value1()), other.value3())
                        .when(DOCUMENTS.ID.eq(other.value1()), current.value3())
                        .otherwise(DOCUMENTS.UPDATED_AT))
                .where(DOCUMENTS.ID.in(current.value1(), other.value1()))
                .execute();
        return true;
    }

    /**
     * Append a {@code document_revisions} row. Snapshot of body +
     * frontmatter as they stand AFTER the edit so the revision history
     * reads forward-in-time. Caller is the editing user.
     */
    public void recordRevision(long documentId,
                               String body,
                               JsonNode frontmatter,
                               long editedBy) {
        try {
            DocumentRow current = findByIdIncludingDeleted(documentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Document not found for revision: " + documentId));
            JsonNode effectiveFrontmatter = frontmatter == null
                    ? current.frontmatter()
                    : frontmatter;
            DocumentRow snapshot = new DocumentRow(
                    current.id(),
                    current.slug(),
                    current.title(),
                    current.typeSlug(),
                    current.typeVersion(),
                    current.rev(),
                    body == null ? "" : body,
                    effectiveFrontmatter,
                    current.tags(),
                    current.authorId(),
                    current.visibility(),
                    current.status(),
                    current.createdAt(),
                    current.updatedAt(),
                    current.deletedAt(),
                    current.deletedBy(),
                    current.anchorDocumentId());
            snapshotRevision(snapshot, editedBy, null, false);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DocumentRepository.recordRevision failed: " + e.getMessage(), e);
        }
    }

    /**
     * Add an editor to a doc. Idempotent via {@code ON CONFLICT DO
     * NOTHING} (the composite PK collides on duplicate add).
     */
    public void addEditor(long documentId, long userId) {
        dsl.insertInto(DOCUMENT_EDITORS)
                .set(DOCUMENT_EDITORS.DOCUMENT_ID, documentId)
                .set(DOCUMENT_EDITORS.USER_ID, userId)
                .onConflictDoNothing()
                .execute();
    }

    public void removeEditor(long documentId, long userId) {
        dsl.deleteFrom(DOCUMENT_EDITORS)
                .where(DOCUMENT_EDITORS.DOCUMENT_ID.eq(documentId))
                .and(DOCUMENT_EDITORS.USER_ID.eq(userId))
                .execute();
    }

    // === Link graph (v1.5 PR-7) ==============================================

    /**
     * Rewrite {@code document_links} for {@code sourceId} against the
     * given body. Parses {@code ~slug} / {@code ~handle/slug} via
     * {@link LinkGraphParser}, resolves slugs to ids, and replaces
     * the source's link rows atomically.
     *
     * <p>Self-links are skipped. Unresolved slugs are silently
     * skipped (not surfaced to the caller — broken refs aren't
     * write errors). Resolution is a single batched
     * {@code WHERE slug IN (?, ?, ?)} query so the operation is
     * O(1) DB roundtrips regardless of body size.
     */
    public void replaceLinksFor(long sourceId, String body) {
        // Always wipe first — even an empty body should clear stale rows.
        dsl.deleteFrom(DOCUMENT_LINKS)
                .where(DOCUMENT_LINKS.SOURCE_ID.eq(sourceId))
                .execute();
        if (body == null || body.isEmpty()) return;
        List<LinkGraphParser.Reference> refs = LinkGraphParser.parse(body);
        if (refs.isEmpty()) return;

        List<String> slugs = refs.stream()
                .map(LinkGraphParser.Reference::slug)
                .toList();
        // Resolve in one query.
        var resolved = dsl.select(DOCUMENTS.ID, DOCUMENTS.SLUG)
                .from(DOCUMENTS)
                .where(DOCUMENTS.SLUG.in(slugs))
                .fetch();
        if (resolved.isEmpty()) return;

        // Order-preserving insert keyed by the slug → id map. Skip
        // self-links and per-target dupes.
        java.util.Map<String, Long> bySlug = new java.util.HashMap<>();
        for (var r : resolved) {
            bySlug.put(r.value2(), r.value1());
        }
        var values = dsl.insertInto(DOCUMENT_LINKS,
                DOCUMENT_LINKS.SOURCE_ID,
                DOCUMENT_LINKS.TARGET_ID,
                DOCUMENT_LINKS.KIND);
        boolean any = false;
        java.util.HashSet<Long> seenTargets = new java.util.HashSet<>();
        for (LinkGraphParser.Reference ref : refs) {
            Long target = bySlug.get(ref.slug());
            if (target == null) continue;
            if (target == sourceId) continue;
            if (!seenTargets.add(target)) continue;
            values = values.values(sourceId, target, "reference");
            any = true;
        }
        if (any) values.execute();
    }

    /**
     * Visibility-aware backlinks: docs that link TO {@code targetId}.
     * Sorted by {@code updated_at DESC}. Used by
     * {@code DocsBacklinksScreen}.
     *
     * @param sessionUserId pass -1 for pre-auth
     */
    public List<DocumentRow> findBacklinks(long targetId,
                                           long sessionUserId,
                                           boolean isSysop) {
        return dsl.select(DOCUMENTS.fields())
                .from(DOCUMENTS)
                .join(DOCUMENT_LINKS).on(
                        DOCUMENT_LINKS.SOURCE_ID.eq(DOCUMENTS.ID))
                .where(DOCUMENT_LINKS.TARGET_ID.eq(targetId))
                .and(visibilityPredicate(sessionUserId, isSysop))
                .orderBy(DOCUMENTS.UPDATED_AT.desc(), DOCUMENTS.ID.desc())
                .limit(20)
                .fetch(this::toRow);
    }

    /** Unfiltered backlinks; caller applies visibility rules. */
    public List<DocumentRow> findBacklinksUnfiltered(long targetId) {
        return dsl.select(DOCUMENTS.fields())
                .from(DOCUMENTS)
                .join(DOCUMENT_LINKS).on(
                        DOCUMENT_LINKS.SOURCE_ID.eq(DOCUMENTS.ID))
                .where(DOCUMENT_LINKS.TARGET_ID.eq(targetId))
                .and(DOCUMENTS.DELETED_AT.isNull())
                .orderBy(DOCUMENTS.UPDATED_AT.desc(), DOCUMENTS.ID.desc())
                .limit(50)
                .fetch(this::toRow);
    }

    /** Incoming-link counts for a batch of document ids. */
    public Map<Long, Long> incomingLinkCounts(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Map.of();
        return dsl.select(DOCUMENT_LINKS.TARGET_ID, DSL.count())
                .from(DOCUMENT_LINKS)
                .where(DOCUMENT_LINKS.TARGET_ID.in(documentIds))
                .groupBy(DOCUMENT_LINKS.TARGET_ID)
                .fetchMap(DOCUMENT_LINKS.TARGET_ID, r -> r.value2().longValue());
    }

    // === Faceted-nav read paths (v1.5 PR-5) ==================================

    /**
     * Visibility predicate per {@code DocumentView.canRead}, encoded
     * as SQL so the filter applies at scan time on potentially
     * thousands of rows. Public docs always pass; the session's own
     * authored docs pass; sysop sees everything; explicit editors
     * pass via {@code EXISTS} on {@code document_editors}.
     *
     * <p>Pre-auth callers pass {@code sessionUserId == -1} — the
     * predicate then collapses to "public only" because {@code
     * author_id = -1} matches nothing and the editors EXISTS subquery
     * also matches nothing.
     */
    private static Condition visibilityPredicate(long sessionUserId, boolean isSysop) {
        Condition isLive = DOCUMENTS.DELETED_AT.isNull();
        if (isSysop) {
            return isLive;
        }
        Condition isPublic = DOCUMENTS.VISIBILITY.eq(Visibility.PUBLIC.wireValue());
        if (sessionUserId < 0) {
            return isLive.and(isPublic);
        }
        Condition isAuthor = DOCUMENTS.AUTHOR_ID.eq(sessionUserId);
        Condition isEditor = DSL.exists(
                DSL.selectOne()
                        .from(DOCUMENT_EDITORS)
                        .where(DOCUMENT_EDITORS.DOCUMENT_ID.eq(DOCUMENTS.ID))
                        .and(DOCUMENT_EDITORS.USER_ID.eq(sessionUserId)));
        return isLive.and(isPublic.or(isAuthor).or(isEditor));
    }

    /**
     * Apply a {@link DocumentFilter}'s facets as additional conditions.
     * Tags compose as intersection ({@code @>} array containment).
     * Year and month operate on {@code updated_at}.
     */
    private static Condition filterConditions(DocumentFilter filter) {
        Condition c = DSL.noCondition();
        if (filter.kind().isPresent()) {
            c = c.and(DOCUMENTS.TYPE_SLUG.eq(filter.kind().get().wireValue()));
        }
        if (!filter.tagsList().isEmpty()) {
            // Postgres array containment: tags @> ARRAY['a','b']
            // Explicit ::text[] cast — the column is text[] but jOOQ binds
            // String[] as varchar[], and Postgres has no `text[] @> varchar[]`
            // operator. Casting the parameter side to text[] resolves the
            // operator lookup.
            String[] needed = filter.tagsList().toArray(new String[0]);
            c = c.and(DSL.condition("{0} @> {1}::text[]",
                    DOCUMENTS.TAGS,
                    DSL.val(needed, String[].class)));
        }
        if (!filter.excludedTagsList().isEmpty()) {
            // PR-6: -tag:foo. NOT (tags && ARRAY['foo','bar']) excludes
            // any doc carrying any of the excluded tags. Same ::text[] cast
            // as above — `&&` (overlap) needs matching array element types.
            String[] excluded = filter.excludedTagsList().toArray(new String[0]);
            c = c.and(DSL.condition("NOT ({0} && {1}::text[])",
                    DOCUMENTS.TAGS,
                    DSL.val(excluded, String[].class)));
        }
        if (filter.authorId().isPresent()) {
            c = c.and(DOCUMENTS.AUTHOR_ID.eq(filter.authorId().get()));
        }
        if (filter.year().isPresent()) {
            int y = filter.year().get();
            if (filter.month().isPresent()) {
                int m = filter.month().get();
                c = c.and(DSL.field("date_part('year', updated_at)", Double.class)
                                .eq((double) y))
                     .and(DSL.field("date_part('month', updated_at)", Double.class)
                                .eq((double) m));
            } else {
                c = c.and(DSL.field("date_part('year', updated_at)", Double.class)
                                .eq((double) y));
            }
        }
        if (filter.search().isPresent()) {
            // PR-6: search_vector @@ plainto_tsquery('simple', :q).
            // plainto_tsquery handles tokenisation + AND-composition;
            // 'simple' matches the trigger config (no stemming).
            String q = filter.search().get();
            c = c.and(DSL.condition(
                    "search_vector @@ plainto_tsquery('simple', {0})",
                    DSL.val(q)));
        }
        return c;
    }

    /**
     * Filtered, paged, visibility-aware list. Default sort is
     * {@link DocumentSort#RECENT}; pass a different mode for
     * created / alpha / most-linked.
     *
     * @param sessionUserId pass -1 for pre-auth
     */
    public List<DocumentRow> findByFilter(DocumentFilter filter,
                                          long sessionUserId,
                                          boolean isSysop,
                                          DocumentSort sort,
                                          int offset,
                                          int limit) {
        if (sort == null) sort = DocumentSort.RECENT;
        if (sort == DocumentSort.MOST_LINKED) {
            return findByFilterMostLinked(filter, sessionUserId, isSysop, offset, limit);
        }
        var step = dsl.selectFrom(DOCUMENTS)
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter));
        var ordered = switch (sort) {
            case CREATED -> step.orderBy(DOCUMENTS.CREATED_AT.desc(),
                    DOCUMENTS.ID.desc());
            case ALPHA -> step.orderBy(DSL.lower(DOCUMENTS.TITLE).asc(),
                    DOCUMENTS.ID.desc());
            case RECENT, MOST_LINKED -> step.orderBy(
                    DOCUMENTS.UPDATED_AT.desc(), DOCUMENTS.ID.desc());
        };
        return ordered.limit(limit).offset(offset).fetch(this::toRow);
    }

    /**
     * Backwards-compatible 5-arg overload (PR-5 default sort).
     */
    public List<DocumentRow> findByFilter(DocumentFilter filter,
                                          long sessionUserId,
                                          boolean isSysop,
                                          int offset,
                                          int limit) {
        return findByFilter(filter, sessionUserId, isSysop,
                DocumentSort.RECENT, offset, limit);
    }

    /**
     * MOST_LINKED sort — left-joins {@code document_links} grouped by
     * target id to compute incoming-link counts; orders by count
     * desc, id desc tiebreak. The {@code DocumentRow} projection
     * doesn't carry the count (it's a sort-only signal); we just
     * need the rows in the right order.
     */
    private List<DocumentRow> findByFilterMostLinked(DocumentFilter filter,
                                                     long sessionUserId,
                                                     boolean isSysop,
                                                     int offset,
                                                     int limit) {
        var linkCount = DSL.field("(SELECT COUNT(*) FROM "
                + DOCUMENT_LINKS.getName()
                + " WHERE target_id = " + DOCUMENTS.getName()
                + ".id)", Long.class);
        return dsl.select(DOCUMENTS.fields())
                .from(DOCUMENTS)
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter))
                .orderBy(linkCount.desc(), DOCUMENTS.ID.desc())
                .limit(limit)
                .offset(offset)
                .fetch(this::toRow);
    }

    /** Total docs matching {@code filter} for this session — paging total. */
    public long countByFilter(DocumentFilter filter,
                              long sessionUserId,
                              boolean isSysop) {
        return dsl.selectCount()
                .from(DOCUMENTS)
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter))
                .fetchOne(0, Long.class);
    }

    /**
     * Per-kind counts within the current filter. Returns kinds in the
     * enum's natural order (RELEASE, ARTICLE, etc.); zero-count kinds
     * are omitted. Used by the kind picker screen.
     */
    public Map<DocumentKind, Long> kindFacetCounts(DocumentFilter filter,
                                                   long sessionUserId,
                                                   boolean isSysop) {
        var rows = dsl.select(DOCUMENTS.TYPE_SLUG, DSL.count())
                .from(DOCUMENTS)
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter))
                .groupBy(DOCUMENTS.TYPE_SLUG)
                .fetch();
        Map<DocumentKind, Long> out = new EnumMap<>(DocumentKind.class);
        for (var r : rows) {
            try {
                out.put(DocumentKind.parse(r.value1()), r.value2().longValue());
            } catch (IllegalArgumentException e) {
                // Ignore unknown kinds in the DB (defensive); shouldn't happen
                // since the column is constrained.
            }
        }
        return out;
    }

    /**
     * Top-N tag counts within the current filter, ordered by count
     * desc then tag asc. UNNESTs the {@code tags} array; tags already
     * present in the filter are included (they'd round-trip to the
     * same set if the user "narrowed" by them again — the picker
     * screen filters them out at the UI layer).
     */
    public List<FacetCount.Tag> tagFacetCounts(DocumentFilter filter,
                                               long sessionUserId,
                                               boolean isSysop,
                                               int topN) {
        var rows = dsl.select(
                        DSL.field("unnest(tags)", String.class).as("t"),
                        DSL.count())
                .from(DOCUMENTS)
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter))
                .groupBy(DSL.field("t"))
                .orderBy(DSL.count().desc(), DSL.field("t").asc())
                .limit(topN)
                .fetch();
        return rows.stream()
                .map(r -> new FacetCount.Tag(r.value1(), r.value2().longValue()))
                .toList();
    }

    /**
     * Top-N author counts within the current filter, joining
     * {@code users} for the handle. Ordered by count desc then handle
     * asc.
     */
    public List<FacetCount.Author> authorFacetCounts(DocumentFilter filter,
                                                     long sessionUserId,
                                                     boolean isSysop,
                                                     int topN) {
        var rows = dsl.select(USERS.ID, USERS.HANDLE, DSL.count())
                .from(DOCUMENTS)
                .join(USERS).on(USERS.ID.eq(DOCUMENTS.AUTHOR_ID))
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter))
                .groupBy(USERS.ID, USERS.HANDLE)
                .orderBy(DSL.count().desc(), USERS.HANDLE.asc())
                .limit(topN)
                .fetch();
        return rows.stream()
                .map(r -> new FacetCount.Author(
                        r.value1(), r.value2(), r.value3().longValue()))
                .toList();
    }

    /**
     * Year counts within the current filter. Years are extracted from
     * {@code updated_at}. Returned ordered by year asc so the picker
     * paints the timeline naturally (oldest first); the screen can
     * reverse if needed.
     */
    public List<FacetCount.Year> whenFacetCounts(DocumentFilter filter,
                                                 long sessionUserId,
                                                 boolean isSysop) {
        var yearField = DSL.field("date_part('year', updated_at)", Double.class).as("y");
        var rows = dsl.select(yearField, DSL.count())
                .from(DOCUMENTS)
                .where(visibilityPredicate(sessionUserId, isSysop))
                .and(filterConditions(filter))
                .groupBy(yearField)
                .orderBy(yearField.asc())
                .fetch();
        return rows.stream()
                .map(r -> new FacetCount.Year(
                        r.value1().intValue(), r.value2().longValue()))
                .toList();
    }

    /**
     * jOOQ Record → DocumentRow projection. Centralised so every
     * fetch path produces the same shape. {@link DocumentKind} /
     * {@link Status} parse strictly (throw on unknown);
     * {@link Visibility} parses defensively (unknown → PRIVATE).
     */
    private DocumentRow toRow(Record r) {
        try {
            org.jooq.JSONB fmJsonb = r.get(DOCUMENTS.FRONTMATTER);
            String fmText = fmJsonb == null ? "{}" : fmJsonb.data();
            JsonNode fm = json.readTree(fmText);
            String[] tagsArr = r.get(DOCUMENTS.TAGS);
            List<String> tags = tagsArr == null ? List.of() : List.of(tagsArr);
            UUID anchor = r.get(DOCUMENTS.ANCHOR_DOCUMENT_ID);
            return new DocumentRow(
                    r.get(DOCUMENTS.ID),
                    r.get(DOCUMENTS.SLUG),
                    r.get(DOCUMENTS.TITLE),
                    r.get(DOCUMENTS.TYPE_SLUG),
                    r.get(DOCUMENTS.TYPE_VERSION),
                    r.get(DOCUMENTS.REV),
                    r.get(DOCUMENTS.BODY),
                    fm,
                    tags,
                    r.get(DOCUMENTS.AUTHOR_ID),
                    Visibility.parse(r.get(DOCUMENTS.VISIBILITY)),
                    Status.parse(r.get(DOCUMENTS.STATUS)),
                    r.get(DOCUMENTS.CREATED_AT),
                    r.get(DOCUMENTS.UPDATED_AT),
                    r.get(DOCUMENTS.DELETED_AT),
                    r.get(DOCUMENTS.DELETED_BY),
                    anchor);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to project documents row id="
                    + r.get(DOCUMENTS.ID) + ": " + e.getMessage(), e);
        }
    }

    private int nextRevAfterSave(DocumentRow current) {
        return current.status() == Status.PUBLISHED ? current.rev() + 1 : current.rev();
    }

    private void snapshotPublished(DocumentRow current,
                                   long editedBy,
                                   String summary,
                                   boolean isDeletion) {
        if (current.status() != Status.PUBLISHED) return;
        snapshotRevision(current, editedBy, summary, isDeletion);
    }

    private void snapshotRevision(DocumentRow snapshot,
                                  long editedBy,
                                  String summary,
                                  boolean isDeletion) {
        try {
            String fmText = json.writeValueAsString(
                    snapshot.frontmatter() == null ? json.createObjectNode() : snapshot.frontmatter());
            String[] tagsArr = snapshot.tags() == null
                    ? new String[0]
                    : snapshot.tags().toArray(new String[0]);
            dsl.insertInto(DOCUMENT_REVISIONS)
                    .set(DOCUMENT_REVISIONS.DOCUMENT_ID, snapshot.id())
                    .set(DOCUMENT_REVISIONS.REV, snapshot.rev())
                    .set(DOCUMENT_REVISIONS.TITLE, snapshot.title())
                    .set(DOCUMENT_REVISIONS.BODY, snapshot.body() == null ? "" : snapshot.body())
                    .set(DOCUMENT_REVISIONS.FRONTMATTER, JSONB.valueOf(fmText))
                    .set(DOCUMENT_REVISIONS.TAGS, tagsArr)
                    .set(DOCUMENT_REVISIONS.VISIBILITY, snapshot.visibility().wireValue())
                    .set(DOCUMENT_REVISIONS.STATUS, snapshot.status().wireValue())
                    .set(DOCUMENT_REVISIONS.TYPE_SLUG, snapshot.typeSlug())
                    .set(DOCUMENT_REVISIONS.TYPE_VERSION, snapshot.typeVersion())
                    .set(DOCUMENT_REVISIONS.EDITED_BY, editedBy)
                    .set(DOCUMENT_REVISIONS.EDIT_SUMMARY, summary)
                    .set(DOCUMENT_REVISIONS.IS_DELETION, isDeletion)
                    .execute();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "DocumentRepository.snapshotRevision failed: " + e.getMessage(), e);
        }
    }
}
