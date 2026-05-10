package io.aeyer.voidcore.documents;

import java.util.Locale;

/**
 * Transitional built-in document kind enum.
 *
 * <p>The canonical type identity now lives in
 * {@code documents.type_slug + type_version}; this enum remains as a
 * convenience for the built-in handlers and older call sites while the
 * screen layer migrates. {@code RELEASE} remains here as a transitional
 * compatibility kind even though release is no longer part of the core
 * built-in fallback/schema set.
 *
 * <p>An unknown wire value indicates migration drift (the schema is
 * ahead of the deployed binary, or vice versa). {@link #parse} throws
 * {@link IllegalArgumentException} rather than guessing — fail loud.
 */
public enum DocumentKind {
    HOWTO, ARTICLE, LINK, GLOSSARY, RELEASE, NOTE;

    /**
     * Parse the wire value (lowercase as stored in the schema).
     * Throws on unknown — see class javadoc for the rationale.
     *
     * @throws IllegalArgumentException if {@code raw} is null or
     *         doesn't match one of the six known kinds
     */
    public static DocumentKind parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "DocumentKind.parse: null is not a valid wire value");
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "howto"    -> HOWTO;
            case "article"  -> ARTICLE;
            case "link"     -> LINK;
            case "glossary" -> GLOSSARY;
            case "release"  -> RELEASE;
            case "note"     -> NOTE;
            default -> throw new IllegalArgumentException(
                    "DocumentKind.parse: unknown wire value '" + raw
                    + "' — schema-binary drift?");
        };
    }

    /** Lowercase wire value as stored in the {@code documents.type_slug} column. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
