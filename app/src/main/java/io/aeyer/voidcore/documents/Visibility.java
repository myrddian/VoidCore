package io.aeyer.voidcore.documents;

import java.util.Locale;

/**
 * Document visibility per SPEC-documents.md §2.1. v1 ships
 * {@code public} and {@code private} only; future migrations may
 * add {@code unlisted}, {@code members_only}, etc.
 *
 * <p>Forward-compat: {@link #parse} returns {@link #PRIVATE} for any
 * unknown wire value. Rationale — a future migration that adds
 * {@code unlisted} shouldn't cause an old binary to inadvertently
 * expose a doc as public. "Unknown means private" is the safe
 * failure mode while a richer permission model lands.
 *
 * <p>The schema's CHECK constraint is the source of truth for which
 * values are legal in the database; this enum just decides how to
 * interpret what comes back.
 */
public enum Visibility {
    PUBLIC, PRIVATE;

    /**
     * Tolerant parse. Null → PRIVATE; unknown wire value → PRIVATE.
     * Matched case-insensitively but the schema stores lowercase.
     */
    public static Visibility parse(String raw) {
        if (raw == null) return PRIVATE;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "public"  -> PUBLIC;
            case "private" -> PRIVATE;
            default        -> PRIVATE;  // forward-compat default
        };
    }

    /** Lowercase wire value as stored in the {@code documents.visibility} column. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
