package io.aeyer.voidcore.documents;

/**
 * Sort modes for the documents results screen (PR-6,
 * SPEC-documents §4.3). {@link #cycle()} advances through them in
 * declaration order so the {@code [S]} keystroke cycles
 * deterministically.
 *
 * <ul>
 *   <li>{@link #RECENT} — {@code updated_at DESC} (default)</li>
 *   <li>{@link #CREATED} — {@code created_at DESC}</li>
 *   <li>{@link #ALPHA} — {@code title ASC}</li>
 *   <li>{@link #MOST_LINKED} — incoming-link count DESC, ID DESC
 *       tiebreak.</li>
 * </ul>
 */
public enum DocumentSort {
    RECENT, CREATED, ALPHA, MOST_LINKED;

    /** Next mode in cycle order, wrapping back to RECENT. */
    public DocumentSort cycle() {
        DocumentSort[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Lower-cased enum name, hyphenated for {@code most-linked}. */
    public String wireValue() {
        return switch (this) {
            case RECENT -> "recent";
            case CREATED -> "created";
            case ALPHA -> "alpha";
            case MOST_LINKED -> "most-linked";
        };
    }

    /** Tolerant parse — unknown / null returns {@link #RECENT} (default). */
    public static DocumentSort parse(String s) {
        if (s == null) return RECENT;
        return switch (s.trim().toLowerCase()) {
            case "recent" -> RECENT;
            case "created" -> CREATED;
            case "alpha" -> ALPHA;
            case "most-linked", "most_linked" -> MOST_LINKED;
            default -> RECENT;
        };
    }
}
