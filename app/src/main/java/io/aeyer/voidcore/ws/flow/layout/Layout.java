package io.aeyer.voidcore.ws.flow.layout;

import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;

import java.util.List;

/**
 * Top-level layout returned by a screen. Either a hand-built list of
 * {@link Row}s ({@code FIXED} mode — what every screen used before
 * V1.5) or a tree of {@link Element}s the renderer turns into rows
 * ({@code FLOW} mode — for long-form text per ADR-031).
 *
 * <p>Per ADR-031, V1.5 is server-side only: {@code Flow} is rendered
 * to {@code Row}s at the server-known canvas width (default 80 cols)
 * and shipped on the existing wire format. V2 will extend the wire
 * envelope with a {@code tree} payload that carries the
 * {@link Element} tree directly so the client can lay out against
 * the real canvas.
 *
 * <p>Sealed; only {@link Fixed} and {@link Flow} permitted. Both are
 * immutable records.
 */
public sealed interface Layout {

    /**
     * Pre-built rows. The renderer is the identity function — it
     * just hands the rows back. Existing screens that already build
     * {@code List<Row>} use this without any code change once they
     * route through {@link LayoutRenderer}.
     */
    record Fixed(List<Row> rows) implements Layout {}

    /**
     * A tree of layout elements rooted at {@code root}, to be
     * rendered against {@code canvasCols} columns. The renderer
     * walks the tree depth-first and emits rows.
     *
     * <p>{@code canvasCols} is the assumed canvas width for V1.5
     * server-side rendering. The {@code main} region in v1 is sized
     * for 80 columns; that's the default. Screens may override for
     * known wider areas.
     */
    record Flow(Element root, int canvasCols) implements Layout {

        /** Default canvas: 80 columns (the v1 {@code main} region width). */
        public static final int DEFAULT_COLS = 80;

        /** Convenience: flow at the default canvas width. */
        public Flow(Element root) {
            this(root, DEFAULT_COLS);
        }
    }
}
