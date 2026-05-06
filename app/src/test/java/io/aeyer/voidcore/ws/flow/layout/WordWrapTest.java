package io.aeyer.voidcore.ws.flow.layout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Greedy space-only wrap. No hyphenation; long words overflow on
 * their own line. Per SPEC-layout.md §3.2.
 */
class WordWrapTest {

    @Test
    void emptyInputYieldsSingleEmptyLine() {
        assertThat(WordWrap.wrap("", 80)).containsExactly("");
        assertThat(WordWrap.wrap(null, 80)).containsExactly("");
    }

    @Test
    void shortTextFitsOnOneLine() {
        assertThat(WordWrap.wrap("hello world", 80)).containsExactly("hello world");
    }

    @Test
    void wrapsAtSpaceBoundary() {
        // "hello world foo" = 15 chars. Wrap at 11 → "hello world" + "foo".
        assertThat(WordWrap.wrap("hello world foo", 11))
                .containsExactly("hello world", "foo");
    }

    @Test
    void wrapsAtExactBoundary() {
        assertThat(WordWrap.wrap("aaa bbb ccc", 7))
                .containsExactly("aaa bbb", "ccc");
    }

    @Test
    void singleWordLongerThanColsOverflows() {
        // No hyphenation; long word goes on its own line.
        assertThat(WordWrap.wrap("supercalifragilistic", 5))
                .containsExactly("supercalifragilistic");
    }

    @Test
    void mixedShortAndLongWords() {
        assertThat(WordWrap.wrap("hi supercalifragilistic ok", 5))
                .containsExactly("hi", "supercalifragilistic", "ok");
    }

    @Test
    void multipleSpacesCollapse() {
        assertThat(WordWrap.wrap("hello    world", 80)).containsExactly("hello world");
    }

    @Test
    void leadingAndTrailingSpacesIgnored() {
        assertThat(WordWrap.wrap("  hello world  ", 80)).containsExactly("hello world");
    }

    @Test
    void zeroOrNegativeColsReturnsSingleLine() {
        // Defensive: don't loop forever on a zero-width canvas.
        assertThat(WordWrap.wrap("anything goes", 0)).containsExactly("anything goes");
        assertThat(WordWrap.wrap("anything goes", -5)).containsExactly("anything goes");
    }

    @Test
    void packsGreedily() {
        // Greedy: take as much as fits before breaking.
        // "the quick brown fox" → at cols=10: "the quick", "brown fox"
        assertThat(WordWrap.wrap("the quick brown fox", 10))
                .containsExactly("the quick", "brown fox");
    }

    @Test
    void singleWordExactlyAtCols() {
        assertThat(WordWrap.wrap("exactly12345", 12)).containsExactly("exactly12345");
    }

    @Test
    void wrapsMultipleTimes() {
        String input = "a b c d e f g h i j";
        // cols=5 → "a b c", "d e f", "g h i", "j"
        List<String> got = WordWrap.wrap(input, 5);
        assertThat(got).containsExactly("a b c", "d e f", "g h i", "j");
    }
}
