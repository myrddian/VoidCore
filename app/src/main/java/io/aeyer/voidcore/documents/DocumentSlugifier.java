package io.aeyer.voidcore.documents;

import java.util.function.Predicate;

/**
 * Pure-value slug generator for new documents (PR-4b). Title →
 * lowercase, non-alphanumeric runs collapsed to a single hyphen,
 * leading/trailing hyphens trimmed. Collisions resolved by appending
 * {@code -2}, {@code -3}, ... until a free slug is found.
 *
 * <p>Pure function so collision handling is testable without a DB:
 * the caller passes an {@code exists} predicate.
 */
public final class DocumentSlugifier {

    private DocumentSlugifier() {}

    /**
     * Build a unique slug from {@code title}, falling back to
     * {@code "untitled"} if the title contains no slug-safe
     * characters. {@code exists} is consulted to find a free
     * suffix on collision.
     */
    public static String slugify(String title, Predicate<String> exists) {
        String base = baseSlug(title);
        if (!exists.test(base)) return base;
        int i = 2;
        while (exists.test(base + "-" + i)) i++;
        return base + "-" + i;
    }

    /** First-pass slug without collision handling. Visible for tests. */
    public static String baseSlug(String title) {
        if (title == null) return "untitled";
        String lower = title.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastWasHyphen = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (alnum) {
                sb.append(c);
                lastWasHyphen = false;
            } else {
                // Non-alphanumeric → single hyphen separator;
                // collapse consecutive runs.
                if (!lastWasHyphen && sb.length() > 0) {
                    sb.append('-');
                    lastWasHyphen = true;
                }
            }
        }
        // Trim trailing hyphen.
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        if (sb.length() == 0) return "untitled";
        return sb.toString();
    }
}
