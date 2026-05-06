package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A row in the {@code documents} table per SPEC-documents.md §2.1.
 * The Java-side analog to a backfilled or freshly-authored document.
 *
 * <p>{@link #frontmatter} is a {@link JsonNode} — type-specific
 * shapes are decoded by the per-kind renderer (PR-3) into typed
 * helpers like {@code ReleaseFrontmatter.from(JsonNode)}; the repo
 * stays kind-agnostic and just hands back the JSON.
 *
 * <p>{@code search_vector} is intentionally absent — it's an
 * internal index, no consumer reads it directly.
 *
 * @param anchorDocumentId UUID that points at an Anchor (ADR-024)
 *        ingested document; null until the integration lands.
 */
public record DocumentRow(
        long id,
        String slug,
        String title,
        String typeSlug,
        int typeVersion,
        int rev,
        String body,
        JsonNode frontmatter,
        List<String> tags,
        long authorId,
        Visibility visibility,
        Status status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt,
        Long deletedBy,
        UUID anchorDocumentId
) {
    @Deprecated
    public DocumentRow(long id,
                       String slug,
                       String title,
                       DocumentKind kind,
                       String body,
                       JsonNode frontmatter,
                       List<String> tags,
                       long authorId,
                       Visibility visibility,
                       Status status,
                       OffsetDateTime createdAt,
                       OffsetDateTime updatedAt,
                       UUID anchorDocumentId) {
        this(id, slug, title, kind.wireValue(), 1, 1, body, frontmatter, tags,
                authorId, visibility, status, createdAt, updatedAt, null, null,
                anchorDocumentId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public java.util.Optional<BuiltinType> builtinType() {
        return BuiltinType.bySlug(typeSlug);
    }

    @Deprecated
    public DocumentKind kind() {
        return DocumentKind.parse(typeSlug);
    }
}
