package io.aeyer.voidcore.documents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code ~slug} and {@code ~handle/slug} cross-references out
 * of a document body (PR-7, SPEC-documents §6). Pure value — no DB
 * access, no callbacks.
 *
 * <h2>Grammar</h2>
 *
 * <pre>
 * TOKEN := '~' SLUG ('/' SLUG)?
 * SLUG  := [a-z][a-z0-9-]*
 * </pre>
 *
 * <p>A reference must be preceded by start-of-line, whitespace, or
 * one of <code>(</code> <code>[</code> <code>{</code> <code>,</code>
 * <code>.</code> <code>;</code> <code>:</code> so that mid-word
 * matches like {@code user~slug} are not picked up. The regex uses
 * a lookbehind for that boundary.
 *
 * <p>For {@code ~handle/slug}, the {@code handle} is preserved on
 * {@link Reference#handle()} as a display hint but the lookup key
 * is always {@link Reference#slug()} — slugs are globally UNIQUE in
 * v1 (per V6 migration).
 *
 * <p>References are returned in order of first appearance, de-duped
 * (a body referencing the same slug five times produces one row).
 */
public final class LinkGraphParser {

    /**
     * Pattern (uncompiled regex source). Lookbehind enforces the
     * left-boundary; the alternation in the body matches either
     * {@code ~handle/slug} or just {@code ~slug}.
     */
    private static final Pattern PATTERN = Pattern.compile(
            "(?<=^|[\\s(\\[{,.;:])"
                    + "~([a-z][a-z0-9-]*)(?:/([a-z][a-z0-9-]*))?",
            Pattern.MULTILINE);

    private LinkGraphParser() {}

    /**
     * Extract slug references from {@code body}. Returns an
     * order-preserving, de-duplicated list. Null / empty input
     * returns an empty list.
     */
    public static List<Reference> parse(String body) {
        if (body == null || body.isEmpty()) return List.of();
        // LinkedHashMap-of-slug semantics via a marker set: track
        // seen slugs to avoid duplicates while preserving order.
        LinkedHashSet<Reference> out = new LinkedHashSet<>();
        Matcher m = PATTERN.matcher(body);
        while (m.find()) {
            String first = m.group(1);
            String second = m.group(2);
            if (second == null) {
                // ~slug — first capture IS the slug.
                out.add(new Reference(null, first));
            } else {
                // ~handle/slug — first is handle (hint), second is slug.
                out.add(new Reference(first, second));
            }
        }
        // Final de-dup by slug only (different handles for the same
        // slug should still resolve to one document_links row).
        LinkedHashSet<String> seenSlugs = new LinkedHashSet<>();
        java.util.ArrayList<Reference> result = new java.util.ArrayList<>();
        for (Reference r : out) {
            if (seenSlugs.add(r.slug())) result.add(r);
        }
        return List.copyOf(result);
    }

    /**
     * One parsed reference. {@code handle} is the optional display
     * hint from {@code ~handle/slug}; null when only {@code ~slug}
     * was used.
     */
    public record Reference(String handle, String slug) {}
}
