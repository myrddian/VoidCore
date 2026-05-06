package io.aeyer.voidcore.documents;

/**
 * Static metadata describing one editable frontmatter field on a
 * given {@link DocumentKind}. Used by the PR-4c frontmatter editor
 * to render the per-kind menu and to type-check field-edit input.
 *
 * @param letter UI keystroke that selects this field on the menu
 * @param key    JSON key inside {@code documents.frontmatter}
 * @param label  Human-readable label for the menu / prompt
 * @param type   Parser hint for the field-edit screen
 */
public record FrontmatterField(
        char letter,
        String key,
        String label,
        Type type
) {

    /** Field types the editor knows how to parse + validate. */
    public enum Type {
        /** Free text. Empty input clears the field. */
        STRING,
        /** Whole number. Empty clears; non-numeric → reject. */
        INTEGER,
        /** {@code true}/{@code false}/{@code yes}/{@code no}/{@code y}/{@code n}. */
        BOOLEAN,
        /** Must start with {@code http://} or {@code https://}; empty clears. */
        URL
    }
}
