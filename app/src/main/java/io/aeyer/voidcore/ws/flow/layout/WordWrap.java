package io.aeyer.voidcore.ws.flow.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy space-only word-wrap used by {@link LayoutRenderer} for
 * {@link Element.Para}. No hyphenation; long words exceeding the
 * canvas overflow on a single line.
 *
 * <p>Per SPEC-layout.md §3.2.
 */
public final class WordWrap {

    private WordWrap() {}

    /**
     * Wrap {@code text} to lines of at most {@code cols} characters.
     * Returns at least one line (an empty input yields a single
     * empty string).
     *
     * <p>Algorithm: split on runs of spaces, greedily pack words onto
     * the current line. If the current line is empty and the next
     * word alone exceeds {@code cols}, the word is emitted on its
     * own line (overflow rather than break).
     */
    public static List<String> wrap(String text, int cols) {
        if (cols <= 0) return List.of(text == null ? "" : text);
        if (text == null || text.isEmpty()) return List.of("");

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" +", -1)) {
            if (word.isEmpty()) continue;
            int candidateLen = current.length() == 0
                    ? word.length()
                    : current.length() + 1 + word.length();
            if (candidateLen <= cols) {
                if (current.length() > 0) current.append(' ');
                current.append(word);
            } else {
                if (current.length() == 0) {
                    // Single word longer than the canvas — overflow on
                    // one line rather than break the word.
                    out.add(word);
                } else {
                    out.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
            }
        }
        if (current.length() > 0) out.add(current.toString());
        if (out.isEmpty()) out.add("");
        return out;
    }
}
