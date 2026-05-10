package io.aeyer.voidcore.ws.flow.layout;

import io.aeyer.voidcore.ws.flow.screen.ScreenText;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a {@link Layout} and produces a list of {@link Row} frames.
 * Per SPEC-layout.md §3.
 *
 * <p>{@link Layout.Fixed} is the identity — its rows are returned
 * unchanged. {@link Layout.Flow} walks the {@link Element} tree
 * depth-first, accumulating rows as it goes.
 *
 * <p>Stateless and side-effect free.
 */
public final class LayoutRenderer {

    private LayoutRenderer() {}

    /** The truncation marker shown when {@link Element.Text} overflows. */
    private static final String ELLIPSIS = "…";

    /** Render a layout to wire-format rows starting at row index 0. */
    public static List<Row> render(Layout layout) {
        return render(layout, 0);
    }

    /**
     * Render a layout to wire-format rows starting at {@code startRow}.
     * Useful when a screen mixes hand-built fixed rows with a Flow
     * subsection — render the subsection at the next available row,
     * then concat into the screen's row list.
     */
    public static List<Row> render(Layout layout, int startRow) {
        return switch (layout) {
            case Layout.Fixed f -> shift(f.rows(), startRow);
            case Layout.Flow flow -> renderFlow(flow, startRow);
        };
    }

    private static List<Row> shift(List<Row> rows, int by) {
        if (by == 0) return rows;
        List<Row> out = new ArrayList<>(rows.size());
        for (Row r : rows) out.add(new Row(r.row() + by, r.spans()));
        return out;
    }

    private static List<Row> renderFlow(Layout.Flow flow, int startRow) {
        List<Row> out = new ArrayList<>();
        emit(flow.root(), flow.canvasCols(), out, startRow, "", null);
        return out;
    }

    /**
     * Recursive emit. Appends rows to {@code out}, returns the row
     * count emitted (so callers — chiefly {@link Element.VStack} —
     * can advance their row counter).
     *
     * <p>{@code leftPad} is a string of spaces prepended to the
     * leading span of every emitted row (decorator-style; cumulative
     * across nested {@link Element.Padded}).
     *
     * <p>{@code styleOverride}, when non-null, replaces the {@code fg}
     * of every emitted span (innermost {@link Element.Styled} wins
     * per the recursion shape).
     */
    private static int emit(Element element,
                            int cols,
                            List<Row> out,
                            int rowOffset,
                            String leftPad,
                            String styleOverride) {
        return switch (element) {
            case Element.Shell(String variant, Element top, Element left, Element body, Element right, Element bottom) -> 0;
            case Element.VStack(List<Element> children, int gap) ->
                    emitVStack(children, gap, cols, out, rowOffset, leftPad, styleOverride);
            case Element.Text(String content, String style) ->
                    emitText(content, resolveStyle(style, styleOverride), cols, out, rowOffset, leftPad);
            case Element.Para(String content, String style) ->
                    emitPara(content, resolveStyle(style, styleOverride), cols, out, rowOffset, leftPad);
            case Element.AnsiBlock(List<Element.AnsiLine> rows) -> 0;
            case Element.Rule r ->
                    emitRule(cols, out, rowOffset, leftPad, styleOverride);
            case Element.Spacer(int rows) ->
                    emitSpacer(rows, out, rowOffset, leftPad);
            case Element.Padded(Element child, int leftCols) ->
                    emit(child, Math.max(0, cols - leftCols), out, rowOffset,
                            leftPad + " ".repeat(Math.max(0, leftCols)), styleOverride);
            case Element.Styled(Element child, String style) ->
                    // Innermost wins: pass the new style down, replacing
                    // any outer override. Each subtree resolves to the
                    // closest enclosing Styled at emit time.
                    emit(child, cols, out, rowOffset, leftPad, style);

            // ─── Interactive widgets ───────────────────────────────────
            // Phase A2+ widgets (Header / StatusLine / KeyMenu /
            // TextField / Editor / Form) are rendered by the client
            // from a tree-payload region.update — not by this
            // server-side layout renderer. They emit zero rows here
            // so the exhaustive switch over the sealed Element type
            // compiles without surfacing an UnsupportedOperationException
            // when one happens to land in a VStack.
            case Element.Header(String title, String rightAnnotation) -> 0;
            case Element.StatusLine(String mode, String left, String right) -> 0;
            case Element.KeyMenu(List<Element.KeyMenu.KeyEntry> entries) -> 0;
            case Element.TextField(String id, String label, String value, Integer maxLength, boolean readOnly) -> 0;
            case Element.Editor(String id, String content, String mode, String syntaxMode, boolean readOnly) -> 0;
            case Element.Form(String id, List<Element> children, String focusedChildId) -> 0;
        };
    }

    private static int emitVStack(List<Element> children, int gap, int cols,
                                  List<Row> out, int rowOffset,
                                  String leftPad, String styleOverride) {
        int produced = 0;
        for (int i = 0; i < children.size(); i++) {
            int childRows = emit(children.get(i), cols, out,
                    rowOffset + produced, leftPad, styleOverride);
            produced += childRows;
            if (gap > 0 && i < children.size() - 1) {
                for (int g = 0; g < gap; g++) {
                    out.add(blankRow(rowOffset + produced, leftPad));
                    produced++;
                }
            }
        }
        return produced;
    }

    private static int emitText(String content, String style, int cols,
                                List<Row> out, int rowOffset, String leftPad) {
        String safe = content == null ? "" : content;
        // Truncate at cols (the canvas; leftPad is outside). With ellipsis
        // if truncation actually happened.
        String shown = safe.length() <= cols
                ? safe
                : safe.substring(0, Math.max(0, cols - ELLIPSIS.length())) + ELLIPSIS;
        out.add(new Row(rowOffset, List.of(new Span(leftPad + shown, style, null, null))));
        return 1;
    }

    private static int emitPara(String content, String style, int cols,
                                List<Row> out, int rowOffset, String leftPad) {
        String safe = content == null ? "" : content;
        // Hard-break semantic: every "\n" ends a wrapping segment.
        // Empty segments (from "\n\n", a leading "\n", a trailing "\n",
        // or runs of newlines) emit a blank row each, preserving the
        // visual gap the author typed.
        //
        // This replaced an earlier soft-break semantic ("\n" → space,
        // "\n\n" → paragraph break) that flattened list formatting in
        // prod content. Almost no real user-typed text uses single
        // newlines as soft breaks; the hard-break default matches the
        // intent of every author who writes a multi-line body and
        // expects to see multiple lines.
        String[] segments = safe.split("\n", -1);
        int produced = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                out.add(blankRow(rowOffset + produced, leftPad));
                produced++;
            } else {
                for (String line : WordWrap.wrap(segment, cols)) {
                    out.add(new Row(rowOffset + produced,
                            List.of(new Span(leftPad + line, style, null, null))));
                    produced++;
                }
            }
        }
        return produced;
    }

    private static int emitRule(int cols, List<Row> out, int rowOffset,
                                String leftPad, String styleOverride) {
        String style = styleOverride == null ? "default" : styleOverride;
        out.add(new Row(rowOffset, List.of(new Span(
                leftPad + ScreenText.repeat("-", Math.max(0, cols)), style, null, null))));
        return 1;
    }

    private static int emitSpacer(int rows, List<Row> out, int rowOffset, String leftPad) {
        int n = Math.max(0, rows);
        for (int i = 0; i < n; i++) {
            out.add(blankRow(rowOffset + i, leftPad));
        }
        return n;
    }

    private static Row blankRow(int rowN, String leftPad) {
        // The leftPad is meaningless on a truly blank row (no content
        // to indent), so emit a clean empty span. Keeps the wire small.
        return new Row(rowN, List.of(new Span("", null, null, null)));
    }

    private static String resolveStyle(String elementStyle, String override) {
        return override != null ? override : (elementStyle == null ? "default" : elementStyle);
    }
}
