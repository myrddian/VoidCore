package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import io.aeyer.voidcore.ws.flow.Frames;

import java.util.List;

/**
 * Text-formatting helpers used by {@link Screen} implementations when
 * building cell content. Static utility class — no state, no side
 * effects beyond the {@link #appendMetaIfSet(List, int, String, String)}
 * helper which appends to a caller-supplied list.
 *
 * <p>Extracted from {@code ScreenRouter} as part of v1.4 PR-B so
 * Screen classes can do their own rendering without going through
 * router back-references.
 */
public final class ScreenText {

    private ScreenText() {}

    /** Repeat a string {@code n} times. Used for frame borders. */
    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** Right-pad with spaces to {@code width}; truncates if longer. */
    public static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }

    /** Left-pad with spaces to {@code width}. */
    public static String padLeft(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    /** Truncate to {@code max} chars, replacing trailing char with '…'. */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Format a byte count as a short human string (1024B, 12.3K, 4.7M, etc.). */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1fK", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1fM", mb);
        double gb = mb / 1024.0;
        return String.format("%.1fG", gb);
    }

    /**
     * Append {@code "  label : value"} only if {@code value} is non-blank;
     * returns the next row index. Used by NFO viewer + sysop edit pane.
     */
    public static int appendMetaIfSet(List<Row> rows, int rowN, String label, String value) {
        if (value == null || value.isBlank()) return rowN;
        rows.add(Frames.row(rowN,
                Frames.span("  " + label + ": ", "grey"),
                Frames.span(value, "default")));
        return rowN + 1;
    }
}
