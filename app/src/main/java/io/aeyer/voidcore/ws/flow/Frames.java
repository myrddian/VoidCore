package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.flow.layout.Layout;
import io.aeyer.voidcore.ws.flow.layout.LayoutRenderer;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny builder helpers for the Row/Span cell-content format (SPEC §4.4).
 * Screen flow code stays readable; the construction details for nested
 * lists stay here.
 */
public final class Frames {

    private Frames() {}

    /** Build a single row of plain text in the default colour. */
    public static Row text(int row, String s) {
        return new Row(row, List.of(new Span(s, "default", null, null)));
    }

    public static Row colored(int row, String s, String fg) {
        return new Row(row, List.of(new Span(s, fg, null, null)));
    }

    public static Row blank(int row) {
        return new Row(row, List.of(new Span("", null, null, null)));
    }

    public static Span span(String s, String fg) {
        return new Span(s, fg, null, null);
    }

    public static Span span(String s, String fg, boolean bold) {
        return new Span(s, fg, null, bold);
    }

    public static Row row(int n, Span... spans) {
        return new Row(n, List.of(spans));
    }

    /** Convert a flat list of strings into rows in the default fg, sequentially. */
    public static List<Row> textRows(List<String> lines, String fg) {
        List<Row> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add(new Row(i, List.of(new Span(lines.get(i), fg, null, null))));
        }
        return out;
    }

    public static ServerMessage.RegionUpdate update(String region, int version, List<Row> rows) {
        return new ServerMessage.RegionUpdate(region, version, rows, null, null);
    }

    /**
     * {@code FLOW}-mode update per ADR-031 / SPEC-layout.md §4. Runs
     * the layout renderer to produce rows at the V1.5 canvas width
     * and ships them with {@code mode: "flow"} on the wire.
     *
     * <p>Companion to {@link #tree} (v1.6 tree-mode update). flow()
     * pre-renders the layout server-side and ships rows on the wire;
     * tree() ships the Element tree directly and lets the client render.
     */
    public static ServerMessage.RegionUpdate flow(String region, int version, Layout layout) {
        List<Row> rows = LayoutRenderer.render(layout);
        return new ServerMessage.RegionUpdate(region, version, rows, null, "flow");
    }

    /**
     * Mixed-content update: a screen has built its own row list
     * (typically combining hand-built fixed rows with rows produced
     * by {@link LayoutRenderer#render(Layout, int)} for a
     * {@code FLOW} subsection), and wants the wire envelope marked
     * {@code mode: "flow"} so the region is V2-upgrade-ready.
     */
    public static ServerMessage.RegionUpdate flow(String region, int version, List<Row> rows) {
        return new ServerMessage.RegionUpdate(region, version, rows, null, "flow");
    }

    /**
     * Tree-mode region update per the v1.6 framework. The client walks
     * the {@link Element} tree and produces DOM. Coexists with row/flow
     * payloads — they remain valid for screens that don't use ScreenApp.
     *
     * @param region    target region name (e.g. "main")
     * @param version   monotonically-increasing version per (region, session)
     * @param tree      root element
     * @param focusPath dotted path to the focused widget, or null
     */
    public static ServerMessage.RegionUpdate
            tree(String region, int version,
                 Element tree, String focusPath) {
        return io.aeyer.voidcore.ws.protocol.ServerMessage.RegionUpdate
                .ofTree(region, version, tree, focusPath);
    }

    public static ServerMessage.RegionAppend append(String region, int version, List<Row> rows) {
        return new ServerMessage.RegionAppend(region, version, rows);
    }

    public static ServerMessage.RegionNotify notify(String region, String text, String level, long durationMs) {
        Row r = new Row(0, List.of(new Span("*** " + text + " ***", null, null, null)));
        return new ServerMessage.RegionNotify(region, List.of(r), durationMs, level);
    }
}
