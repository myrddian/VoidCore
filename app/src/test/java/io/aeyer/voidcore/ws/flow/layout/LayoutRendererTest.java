package io.aeyer.voidcore.ws.flow.layout;

import io.aeyer.voidcore.ws.flow.layout.Element.Padded;
import io.aeyer.voidcore.ws.flow.layout.Element.Para;
import io.aeyer.voidcore.ws.flow.layout.Element.Rule;
import io.aeyer.voidcore.ws.flow.layout.Element.Spacer;
import io.aeyer.voidcore.ws.flow.layout.Element.Styled;
import io.aeyer.voidcore.ws.flow.layout.Element.Text;
import io.aeyer.voidcore.ws.flow.layout.Element.VStack;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the SPEC-layout.md §3 walk: row-numbering monotonic from
 * 0, indent prefix from {@link Padded} prepended to leading span,
 * style override from {@link Styled} rewriting fg, and per-element
 * row-count behaviour.
 */
class LayoutRendererTest {

    @Test
    void fixedIsIdentity() {
        List<Row> input = List.of(
                new Row(0, List.of(new Span("hi", "default", null, null))),
                new Row(1, List.of(new Span("there", "grey", null, null))));
        List<Row> got = LayoutRenderer.render(new Layout.Fixed(input));
        assertThat(got).isSameAs(input);
    }

    @Test
    void textProducesOneRow() {
        var got = render(new Text("hello", "bright_yellow"));
        assertThat(got).hasSize(1);
        assertThat(got.get(0).row()).isZero();
        assertThat(got.get(0).spans().get(0).text()).isEqualTo("hello");
        assertThat(got.get(0).spans().get(0).fg()).isEqualTo("bright_yellow");
    }

    @Test
    void textTruncatesWithEllipsis() {
        // 6-char canvas; "hello world" is 11 — truncated to "hello…".
        var got = render(new Text("hello world", "default"), 6);
        assertThat(got.get(0).spans().get(0).text()).isEqualTo("hello…");
    }

    @Test
    void textAtExactCanvasUntruncated() {
        var got = render(new Text("hi", "default"), 2);
        assertThat(got.get(0).spans().get(0).text()).isEqualTo("hi");
    }

    @Test
    void paraWrapsToCanvas() {
        var got = render(new Para("the quick brown fox", "default"), 10);
        assertThat(rowTexts(got)).containsExactly("the quick", "brown fox");
        // Row indices are 0, 1.
        assertThat(got).extracting(Row::row).containsExactly(0, 1);
    }

    @Test
    void paraSingleNewlineIsHardBreak() {
        // Hard-break semantic: every "\n" ends a segment. Each
        // segment word-wraps independently. Matches author intent
        // (if you typed two lines, you get two lines).
        var got = render(new Para("line one\nline two", "default"), 80);
        assertThat(rowTexts(got)).containsExactly("line one", "line two");
    }

    @Test
    void paraDoubleNewlineEmitsBlankRowBetweenSegments() {
        var got = render(new Para("first\n\nsecond", "default"), 80);
        // split on "\n" → ["first", "", "second"]. Empty segment → blank.
        assertThat(rowTexts(got)).containsExactly("first", "", "second");
    }

    @Test
    void paraTripleNewlineEmitsTwoBlankRows() {
        // Multiple consecutive newlines preserve the visible gap.
        var got = render(new Para("a\n\n\nb", "default"), 80);
        assertThat(rowTexts(got)).containsExactly("a", "", "", "b");
    }

    @Test
    void paraNumberedListPreservesItemBreaks() {
        // The seed "House rules" bulletin shape — list items separated
        // by single newlines should each render on their own line.
        // (This is the regression the V1.5 migration introduced and
        // this fix corrects.)
        var got = render(new Para(
                "1. Be excellent to each other.\n"
                        + "2. Off-topic is fine.\n"
                        + "3. Read the rules.",
                "default"), 80);
        assertThat(rowTexts(got)).containsExactly(
                "1. Be excellent to each other.",
                "2. Off-topic is fine.",
                "3. Read the rules.");
    }

    @Test
    void paraLongSegmentWrapsIndependently() {
        // Each segment wraps within itself; the wrap doesn't reach
        // across a "\n" to pull from the next segment.
        var got = render(new Para("the quick brown\nfox jumps over", "default"), 7);
        assertThat(rowTexts(got)).containsExactly(
                "the",
                "quick",
                "brown",
                "fox",
                "jumps",
                "over");
    }

    @Test
    void emptyParaEmitsOneBlankRow() {
        var got = render(new Para("", "default"), 80);
        assertThat(got).hasSize(1);
        assertThat(rowTexts(got)).containsExactly("");
    }

    @Test
    void spacerEmitsBlankRows() {
        var got = render(new Spacer(3), 80);
        assertThat(got).hasSize(3);
        assertThat(rowTexts(got)).containsExactly("", "", "");
        assertThat(got).extracting(Row::row).containsExactly(0, 1, 2);
    }

    @Test
    void ruleSpansFullCanvas() {
        var got = render(new Rule(), 5);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).spans().get(0).text()).isEqualTo("-----");
    }

    @Test
    void vStackChildrenSerialiseInOrder() {
        var got = render(new VStack(List.of(
                new Text("first"),
                new Text("second"),
                new Text("third"))));
        assertThat(rowTexts(got)).containsExactly("first", "second", "third");
        assertThat(got).extracting(Row::row).containsExactly(0, 1, 2);
    }

    @Test
    void vStackGapInsertsBlankRowsBetweenSiblings() {
        var got = render(new VStack(List.of(
                new Text("a"),
                new Text("b"),
                new Text("c")), 1));
        // "a", "", "b", "", "c" — gap of 1 between each pair.
        assertThat(rowTexts(got)).containsExactly("a", "", "b", "", "c");
    }

    @Test
    void vStackGapNotAppendedAfterLastChild() {
        var got = render(new VStack(List.of(
                new Text("a"), new Text("b")), 2));
        // gap=2 between a and b, none after b.
        assertThat(rowTexts(got)).containsExactly("a", "", "", "b");
    }

    @Test
    void paddedIndentsLeadingSpan() {
        var got = render(new Padded(new Text("hi"), 4), 80);
        assertThat(got.get(0).spans().get(0).text()).isEqualTo("    hi");
    }

    @Test
    void paddedComposes() {
        // Outer pad 2 + inner pad 3 → 5 spaces before "x".
        var got = render(new Padded(new Padded(new Text("x"), 3), 2), 80);
        assertThat(got.get(0).spans().get(0).text()).isEqualTo("     x");
    }

    @Test
    void paddedNarrowsCanvasForChild() {
        // Canvas 10, pad 4 → child wraps at 6.
        // "abc def ghi" with cols=6 → "abc", "def", "ghi" — each prefixed with 4 spaces.
        var got = render(new Padded(new Para("abc def ghi"), 4), 10);
        assertThat(rowTexts(got)).containsExactly("    abc", "    def", "    ghi");
    }

    @Test
    void styledOverridesElementStyle() {
        var got = render(new Styled(new Text("hi", "default"), "bright_yellow"), 80);
        assertThat(got.get(0).spans().get(0).fg()).isEqualTo("bright_yellow");
    }

    @Test
    void styledInnermostWins() {
        // Outer: red. Inner: green. Inner should win.
        var got = render(new Styled(new Styled(new Text("hi", "default"), "green"), "red"), 80);
        assertThat(got.get(0).spans().get(0).fg()).isEqualTo("green");
    }

    @Test
    void styledAppliesToAllRowsInChildSubtree() {
        var got = render(new Styled(new VStack(List.of(
                new Text("one"), new Text("two"))), "bright_red"), 80);
        assertThat(got).hasSize(2);
        assertThat(got.get(0).spans().get(0).fg()).isEqualTo("bright_red");
        assertThat(got.get(1).spans().get(0).fg()).isEqualTo("bright_red");
    }

    @Test
    void mixedTreeProducesContiguousRowIndices() {
        var got = render(new VStack(List.of(
                new Text("Header"),
                new Spacer(1),
                new Para("Body line one. Body line two. Body line three.", "default"),
                new Rule(),
                new Text("Footer"))), 20);
        // Row indices must be 0..N-1 with no gaps.
        for (int i = 0; i < got.size(); i++) {
            assertThat(got.get(i).row()).as("row[%d]", i).isEqualTo(i);
        }
    }

    @Test
    void flowDefaultsTo80Cols() {
        // Sanity: the convenience constructor uses DEFAULT_COLS.
        Layout.Flow flow = new Layout.Flow(new Text("x"));
        assertThat(flow.canvasCols()).isEqualTo(80);
    }

    // --- helpers --------------------------------------------------------

    private static List<Row> render(Element root) {
        return render(root, 80);
    }

    private static List<Row> render(Element root, int cols) {
        return LayoutRenderer.render(new Layout.Flow(root, cols));
    }

    private static List<String> rowTexts(List<Row> rows) {
        return rows.stream().map(r -> r.spans().get(0).text()).toList();
    }
}
