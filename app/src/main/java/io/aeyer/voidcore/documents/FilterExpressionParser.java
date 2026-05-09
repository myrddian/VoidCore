package io.aeyer.voidcore.documents;

import io.aeyer.voidcore.auth.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Pure-value parser for the power-user filter syntax (PR-6,
 * SPEC-documents §4.5). Takes a free-form expression like
 * <pre>kind:howto tag:samples by:SYSOP -tag:beta kick drum</pre>
 * and applies it onto a starting {@link DocumentFilter}, returning
 * the new filter.
 *
 * <h2>Grammar</h2>
 *
 * <ul>
 *   <li>Whitespace-separated tokens.</li>
 *   <li>{@code key:value} → set the matching facet
 *       ({@code kind} / {@code tag} / {@code by} / {@code when}).</li>
 *   <li>{@code -tag:value} → add to excluded tags.</li>
 *   <li>Bare words are joined with a single space and become the
 *       {@code search} value (overwrites any prior search).</li>
 * </ul>
 *
 * <h2>{@code by:} resolution</h2>
 *
 * <p>Numeric value → use directly as user id. Non-numeric → look up
 * via {@link UserRepository#findByHandle}; if unknown, surface a
 * warning via the {@code notifier} callback and skip the facet.
 *
 * <h2>Lenient errors</h2>
 *
 * <p>Unknown facet keys, malformed {@code when} values, and unknown
 * handles all surface a warning via the notifier and are skipped —
 * they don't abort the whole expression. This keeps the [/]
 * surface forgiving for typos.
 */
public final class FilterExpressionParser {

    private final UserRepository users;

    public FilterExpressionParser(UserRepository users) {
        this.users = users;
    }

    /** Apply {@code expression} onto {@code base}; report warnings via {@code notifier}. */
    public DocumentFilter parse(String expression,
                                DocumentFilter base,
                                Consumer<String> notifier) {
        if (base == null) base = DocumentFilter.empty();
        if (expression == null) return base;
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) return base;

        DocumentFilter out = base;
        List<String> bareWords = new ArrayList<>();
        for (String token : trimmed.split("\\s+")) {
            if (token.isEmpty()) continue;
            int colon = token.indexOf(':');
            if (colon < 0) {
                bareWords.add(token);
                continue;
            }
            String key = token.substring(0, colon);
            String value = token.substring(colon + 1);
            if (value.isEmpty()) {
                warn(notifier, "filter token has no value: " + token);
                continue;
            }
            out = applyFacet(out, key, value, notifier);
        }
        if (!bareWords.isEmpty()) {
            out = out.withSearch(String.join(" ", bareWords));
        }
        return out;
    }

    private DocumentFilter applyFacet(DocumentFilter cur,
                                      String key,
                                      String value,
                                      Consumer<String> notifier) {
        return switch (key) {
            case "kind" -> {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    warn(notifier, "unknown kind: " + value);
                    yield cur;
                }
                yield cur.withTypeSlug(normalized);
            }
            case "tag" -> cur.withTag(value);
            case "-tag" -> cur.withExcludedTag(value);
            case "by" -> applyBy(cur, value, notifier);
            case "when" -> applyWhen(cur, value, notifier);
            default -> {
                warn(notifier, "unknown filter facet: " + key);
                yield cur;
            }
        };
    }

    private DocumentFilter applyBy(DocumentFilter cur,
                                   String value,
                                   Consumer<String> notifier) {
        try {
            return cur.withAuthor(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // fall through to handle resolution
        }
        Optional<UserRepository.UserRow> hit = users.findByHandle(value);
        if (hit.isEmpty()) {
            warn(notifier, "user not found: " + value);
            return cur;
        }
        return cur.withAuthor(hit.get().id());
    }

    private static DocumentFilter applyWhen(DocumentFilter cur,
                                            String value,
                                            Consumer<String> notifier) {
        try {
            int dash = value.indexOf('-');
            if (dash < 0) {
                return cur.withYear(Integer.parseInt(value));
            }
            int y = Integer.parseInt(value.substring(0, dash));
            int m = Integer.parseInt(value.substring(dash + 1));
            return cur.withYearMonth(y, m);
        } catch (IllegalArgumentException e) {
            // covers NumberFormatException (subclass) + the
            // withYearMonth out-of-range guard
            warn(notifier, "bad when format (expected YYYY or YYYY-MM): " + value);
            return cur;
        }
    }

    private static void warn(Consumer<String> notifier, String message) {
        if (notifier != null) notifier.accept(message);
    }
}
