package io.aeyer.voidcore.documents;

import java.util.Locale;

/**
 * Document status per SPEC-documents.md §2.1.
 * {@code draft} = author-only work-in-progress;
 * {@code pending} = submitted for moderation;
 * {@code published} = visible per its visibility rules.
 *
 * <p>Like {@link DocumentKind}, the values are a closed set
 * matching the schema's CHECK constraint. Unknown wire values
 * indicate schema-binary drift; {@link #parse} throws.
 *
 * <p>Null tolerated — defaults to {@code PUBLISHED} (the column
 * default).
 */
public enum Status {
    DRAFT, PENDING, PUBLISHED;

    public static Status parse(String raw) {
        if (raw == null) return PUBLISHED;
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "draft"     -> DRAFT;
            case "pending"   -> PENDING;
            case "published" -> PUBLISHED;
            default -> throw new IllegalArgumentException(
                    "Status.parse: unknown wire value '" + raw
                    + "' — schema-binary drift?");
        };
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
