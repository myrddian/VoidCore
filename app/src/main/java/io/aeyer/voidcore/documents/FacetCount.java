package io.aeyer.voidcore.documents;

/**
 * Result rows for the per-facet count queries on
 * {@link DocumentRepository}. Used by the faceted-nav surface (PR-5)
 * to render picker screens with "value: count" summaries within the
 * current filter.
 */
public final class FacetCount {

    private FacetCount() {}

    /** A tag and the number of docs in the current filtered set carrying it. */
    public record Tag(String tag, long count) {}

    /** An author handle (resolved via join) + their doc count in the set. */
    public record Author(long userId, String handle, long count) {}

    /** A calendar year + the doc count for that year within the set. */
    public record Year(int year, long count) {}
}
