package io.aeyer.voidcore.documents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Immutable facet intersection for the documents faceted-navigation
 * surface (PR-5, SPEC-documents §4). Carries the user's current
 * selection — kind, tags, author, when — and produces a canonical
 * serialisation for caching, intent encoding, and deep linking.
 *
 * <p>Mutations return a new {@code DocumentFilter} (record-with-style).
 * The session stores the serialised form in
 * {@link io.aeyer.voidcore.ws.VoidCoreSession#docsFilter}; pickers and the
 * results screen decode → mutate → re-encode through this type.
 *
 * <h2>Tag composition</h2>
 *
 * <p>Tags are intersection (all-of). Selecting a second tag narrows
 * the set further; calling {@link #withTag} multiple times accumulates.
 * The internal list is order-preserving (LinkedHashSet) so the
 * breadcrumb display reflects user-selection order, while
 * {@link #serialise()} sorts alphabetically for the canonical form.
 *
 * <h2>Single-value facets</h2>
 *
 * <p>Kind, author, year/month are single-valued. {@code with*}
 * replaces any existing value rather than appending.
 *
 * <h2>Year + month</h2>
 *
 * <p>Year alone narrows to that calendar year. Year + month narrows
 * to that calendar month. Month without year is invalid (and rejected
 * by {@link #parse}); use the constructor / withWhen helpers and the
 * year is always present when month is.
 */
public record DocumentFilter(
        Optional<String> kind,
        List<String> tags,
        List<String> excludedTags,
        Optional<Long> authorId,
        Optional<Integer> year,
        Optional<Integer> month,
        Optional<String> search
) {

    /** Defensive copy + immutability for both tag lists. */
    public DocumentFilter {
        tags = tags == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(tags));
        excludedTags = excludedTags == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(excludedTags));
    }

    /** No facets selected. */
    public static DocumentFilter empty() {
        return new DocumentFilter(
                Optional.empty(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public boolean isEmpty() {
        return kind.isEmpty() && tags.isEmpty() && excludedTags.isEmpty()
                && authorId.isEmpty() && year.isEmpty() && month.isEmpty()
                && search.isEmpty();
    }

    // ─── with* / drop* mutators ──────────────────────────────────────────

    public DocumentFilter withKind(DocumentKind k) {
        if (k == null) return dropKind();
        return withTypeSlug(k.wireValue());
    }

    public DocumentFilter withTypeSlug(String typeSlug) {
        String normalized = normalizeTypeSlug(typeSlug);
        if (normalized == null) return dropKind();
        return new DocumentFilter(Optional.of(normalized), tags, excludedTags,
                authorId, year, month, search);
    }

    public DocumentFilter dropKind() {
        return new DocumentFilter(Optional.empty(), tags, excludedTags,
                authorId, year, month, search);
    }

    private static String normalizeTypeSlug(String typeSlug) {
        if (typeSlug == null) return null;
        String normalized = typeSlug.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * @deprecated Transitional helper for older built-in-kind callers.
     */
    @Deprecated
    public Optional<DocumentKind> builtinKind() {
        if (kind.isEmpty()) return Optional.empty();
        try {
            return Optional.of(DocumentKind.parse(kind.get()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Append a tag to the intersection (de-duped). Lower-cased so the
     * canonical form is stable regardless of UI capitalisation.
     */
    public DocumentFilter withTag(String t) {
        if (t == null || t.isBlank()) return this;
        String norm = t.toLowerCase().trim();
        if (tags.contains(norm)) return this;
        ArrayList<String> next = new ArrayList<>(tags);
        next.add(norm);
        return new DocumentFilter(kind, next, excludedTags,
                authorId, year, month, search);
    }

    public DocumentFilter dropTag(String t) {
        if (t == null) return this;
        String norm = t.toLowerCase().trim();
        if (!tags.contains(norm)) return this;
        ArrayList<String> next = new ArrayList<>(tags);
        next.remove(norm);
        return new DocumentFilter(kind, next, excludedTags,
                authorId, year, month, search);
    }

    /** Drop ALL tags. Used by {@code [..]} when the most-recent facet was tag-set. */
    public DocumentFilter dropAllTags() {
        return new DocumentFilter(kind, List.of(), excludedTags,
                authorId, year, month, search);
    }

    /**
     * Add an excluded tag (PR-6, {@code -tag:foo}). Excluded tags
     * subtract from the visible set even if they're not in the
     * positive-tags intersection.
     */
    public DocumentFilter withExcludedTag(String t) {
        if (t == null || t.isBlank()) return this;
        String norm = t.toLowerCase().trim();
        if (excludedTags.contains(norm)) return this;
        ArrayList<String> next = new ArrayList<>(excludedTags);
        next.add(norm);
        return new DocumentFilter(kind, tags, next,
                authorId, year, month, search);
    }

    public DocumentFilter dropExcludedTag(String t) {
        if (t == null) return this;
        String norm = t.toLowerCase().trim();
        if (!excludedTags.contains(norm)) return this;
        ArrayList<String> next = new ArrayList<>(excludedTags);
        next.remove(norm);
        return new DocumentFilter(kind, tags, next,
                authorId, year, month, search);
    }

    public DocumentFilter dropAllExcludedTags() {
        return new DocumentFilter(kind, tags, List.of(),
                authorId, year, month, search);
    }

    public DocumentFilter withAuthor(long id) {
        return new DocumentFilter(kind, tags, excludedTags,
                Optional.of(id), year, month, search);
    }

    public DocumentFilter dropAuthor() {
        return new DocumentFilter(kind, tags, excludedTags,
                Optional.empty(), year, month, search);
    }

    /** Year-only — month cleared. */
    public DocumentFilter withYear(int y) {
        return new DocumentFilter(kind, tags, excludedTags, authorId,
                Optional.of(y), Optional.empty(), search);
    }

    /** Year + month. */
    public DocumentFilter withYearMonth(int y, int m) {
        if (m < 1 || m > 12) {
            throw new IllegalArgumentException("month out of range: " + m);
        }
        return new DocumentFilter(kind, tags, excludedTags, authorId,
                Optional.of(y), Optional.of(m), search);
    }

    public DocumentFilter dropWhen() {
        return new DocumentFilter(kind, tags, excludedTags, authorId,
                Optional.empty(), Optional.empty(), search);
    }

    /**
     * Set the full-text search query (PR-6). Single-valued — a fresh
     * search expression overwrites any prior search. Blank → cleared.
     */
    public DocumentFilter withSearch(String q) {
        if (q == null || q.isBlank()) {
            return new DocumentFilter(kind, tags, excludedTags, authorId,
                    year, month, Optional.empty());
        }
        return new DocumentFilter(kind, tags, excludedTags, authorId,
                year, month, Optional.of(q.trim()));
    }

    public DocumentFilter dropSearch() {
        return new DocumentFilter(kind, tags, excludedTags, authorId,
                year, month, Optional.empty());
    }

    // ─── Canonical serialisation ─────────────────────────────────────────

    /**
     * Canonical {@code key=value&key=value} form. Keys appear in
     * alphabetical order (`by`, `kind`, `tag`, `when`); tags sorted
     * alphabetically. Two filters representing the same set produce
     * identical strings — usable as a cache key.
     *
     * <p>Empty filter → empty string.
     */
    public String serialise() {
        if (isEmpty()) return "";
        // Alphabetical fold-in: by, kind, search, tag (positive then
        // excluded), when.
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (authorId.isPresent()) {
            sb.append("by=").append(authorId.get());
            first = false;
        }
        if (kind.isPresent()) {
            if (!first) sb.append('&');
            sb.append("kind=").append(kind.get());
            first = false;
        }
        if (search.isPresent()) {
            if (!first) sb.append('&');
            sb.append("search=").append(percentEncode(search.get()));
            first = false;
        }
        if (!tags.isEmpty()) {
            TreeSet<String> sorted = new TreeSet<>(tags);
            for (String t : sorted) {
                if (!first) sb.append('&');
                sb.append("tag=").append(t);
                first = false;
            }
        }
        if (!excludedTags.isEmpty()) {
            TreeSet<String> sorted = new TreeSet<>(excludedTags);
            for (String t : sorted) {
                if (!first) sb.append('&');
                sb.append("-tag=").append(t);
                first = false;
            }
        }
        if (year.isPresent()) {
            if (!first) sb.append('&');
            sb.append("when=").append(year.get());
            if (month.isPresent()) {
                sb.append('-');
                if (month.get() < 10) sb.append('0');
                sb.append(month.get());
            }
        }
        return sb.toString();
    }

    /**
     * Minimal percent-encoder for search values — encodes
     * {@code &}/{@code =}/{@code +}/space + non-ASCII so the
     * canonical {@code &}-separated form holds.
     */
    private static String percentEncode(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else if (c == ' ') {
                out.append("%20");
            } else {
                out.append('%')
                   .append(String.format("%02X", (int) c & 0xFF));
            }
        }
        return out.toString();
    }

    private static String percentDecode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                String hex = s.substring(i + 1, i + 3);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 2;
                } catch (NumberFormatException e) {
                    out.append(c);
                }
            } else if (c == '+') {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Parse the canonical form. Tolerant of whitespace and ordering
     * (the canonicalisation is on output only — input can be in any
     * key order). Empty / null returns {@link #empty()}.
     *
     * @throws IllegalArgumentException on malformed pairs (no {@code =},
     *         unknown facet name, bad {@code when} format,
     *         non-numeric author / year)
     */
    public static DocumentFilter parse(String s) {
        if (s == null || s.isBlank()) return empty();
        DocumentFilter out = empty();
        for (String pair : s.split("&")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "filter pair missing '=': " + trimmed);
            }
            String key = trimmed.substring(0, eq);
            String val = trimmed.substring(eq + 1);
            switch (key) {
                case "kind" -> out = out.withTypeSlug(val);
                case "tag" -> out = out.withTag(val);
                case "-tag" -> out = out.withExcludedTag(val);
                case "by" -> {
                    try {
                        out = out.withAuthor(Long.parseLong(val));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "filter 'by' not numeric: " + val, e);
                    }
                }
                case "when" -> out = parseWhen(out, val);
                case "search" -> out = out.withSearch(percentDecode(val));
                default -> throw new IllegalArgumentException(
                        "unknown filter facet: " + key);
            }
        }
        return out;
    }

    private static DocumentFilter parseWhen(DocumentFilter cur, String val) {
        try {
            int dash = val.indexOf('-');
            if (dash < 0) {
                int y = Integer.parseInt(val);
                return cur.withYear(y);
            }
            int y = Integer.parseInt(val.substring(0, dash));
            int m = Integer.parseInt(val.substring(dash + 1));
            return cur.withYearMonth(y, m);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "filter 'when' bad format (expected YYYY or YYYY-MM): " + val, e);
        }
    }

    // ─── Pretty / breadcrumb helpers ─────────────────────────────────────

    /**
     * Display string for the breadcrumb header. Like {@link #serialise}
     * but uses {@code /} as the separator (matches the spec mock
     * {@code INFO/kind=howto/tag=samples}). Order follows the
     * iteration order of the underlying fields, which is the same
     * canonical alphabetical fold-in as {@code serialise}.
     */
    public String breadcrumb() {
        return serialise().replace('&', '/');
    }

    /** Read-only view of the active tags, in user-selection order. */
    public List<String> tagsList() {
        return Collections.unmodifiableList(tags);
    }

    /** Read-only view of the excluded tags ({@code -tag:foo}). */
    public List<String> excludedTagsList() {
        return Collections.unmodifiableList(excludedTags);
    }
}
