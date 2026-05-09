package io.aeyer.voidcore.ws.flow.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A node in a {@link Layout.Flow} tree. Per ADR-031 + SPEC-layout.md
 * §2 the vocabulary started small — five elements plus two
 * decorators — so the renderer stays a straight-line walk and the
 * V2 wire format stays small. v1.6 extends it with six interactive
 * widget records (Header, StatusLine, KeyMenu, TextField, Editor, Form)
 * used by the {@code ScreenApp} framework. The renderer for the
 * original layout primitives stays a straight-line walk; interactive
 * widgets aren't rendered server-side (the client owns rendering for
 * the tree-payload region.update variant).
 *
 * <p>Sealed; only the thirteen nested records (seven layout primitives
 * + six interactive widgets) are permitted. All are immutable.
 *
 * <p>Containers are vertical only — no {@code HStack}, no {@code
 * Grid}. Existing tabular surfaces (user list, file list, threads
 * list) stay in {@code FIXED} mode. Adding {@code HStack} later is
 * additive when a screen genuinely needs it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Element.Shell.class,      name = "shell"),
    @JsonSubTypes.Type(value = Element.VStack.class,     name = "vstack"),
    @JsonSubTypes.Type(value = Element.Text.class,       name = "text"),
    @JsonSubTypes.Type(value = Element.Para.class,       name = "para"),
    @JsonSubTypes.Type(value = Element.AnsiBlock.class,  name = "ansiBlock"),
    @JsonSubTypes.Type(value = Element.Rule.class,       name = "rule"),
    @JsonSubTypes.Type(value = Element.Spacer.class,     name = "spacer"),
    @JsonSubTypes.Type(value = Element.Padded.class,     name = "padded"),
    @JsonSubTypes.Type(value = Element.Styled.class,     name = "styled"),
    @JsonSubTypes.Type(value = Element.Header.class,     name = "header"),
    @JsonSubTypes.Type(value = Element.StatusLine.class, name = "statusLine"),
    @JsonSubTypes.Type(value = Element.KeyMenu.class,    name = "keyMenu"),
    @JsonSubTypes.Type(value = Element.TextField.class,  name = "textField"),
    @JsonSubTypes.Type(value = Element.Editor.class,     name = "editor"),
    @JsonSubTypes.Type(value = Element.Form.class,       name = "form"),
})
public sealed interface Element {

    /**
     * Shell wrapper for tree-mode presentation chrome. Gives the browser
     * named regions around the live body tree without flattening it back
     * into rows.
     */
    record Shell(String variant,
                 Element top,
                 Element left,
                 Element body,
                 Element right,
                 Element bottom) implements Element {}

    /**
     * Vertical container. Children render top-to-bottom in
     * declaration order. {@code gap} is the number of blank rows
     * between any two adjacent children (default 0). The implicit
     * root container of any {@link Layout.Flow}.
     */
    record VStack(List<Element> children, int gap) implements Element {

        /** No-gap convenience. */
        public VStack(List<Element> children) {
            this(children, 0);
        }
    }

    /**
     * Single line of text. Does <strong>not</strong> wrap — overflow
     * past {@code canvasCols} is truncated by the renderer with a
     * trailing ellipsis. Use for short fixed-width content: labels,
     * headers, key:value pairs.
     */
    record Text(String content, String style) implements Element {

        /** Default-styled. */
        public Text(String content) { this(content, "default"); }
    }

    /**
     * Wrapping paragraph. The content is split on hard newlines
     * ({@code \n}):
     *
     * <ul>
     *   <li>Single newline ({@code "a\nb"}) → soft break: the renderer
     *       joins {@code a} and {@code b} with a space and word-wraps
     *       the joined text against the canvas width.</li>
     *   <li>Double newline ({@code "a\n\nb"}) → paragraph break: the
     *       renderer emits the wrapped lines for {@code a}, a blank
     *       row, and the wrapped lines for {@code b}.</li>
     * </ul>
     *
     * <p>Word-break is space-only; no hyphenation. Long words exceeding
     * the canvas overflow on a single row (better one ugly line than
     * a broken word — matches BBS aesthetic).
     */
    record Para(String content, String style) implements Element {

        /** Default-styled. */
        public Para(String content) { this(content, "default"); }
    }

    /**
     * Pre-rasterised ANSI/text block for tree-mode shells and other safe
     * decorative regions. Unlike row-mode updates this lives inside the tree.
     */
    record AnsiBlock(List<AnsiLine> rows) implements Element {}

    record AnsiLine(List<AnsiSpan> spans) {}

    record AnsiSpan(String text, String fg, String bg, Boolean bold) {}

    /**
     * Horizontal divider. Renders as {@code -}×canvasCols by default;
     * theme-extensible later. One row.
     */
    record Rule() implements Element {}

    /** {@code n} blank rows. */
    record Spacer(int rows) implements Element {

        /** One blank row. */
        public Spacer() { this(1); }
    }

    /**
     * Decorator. Indents the child by {@code leftCols} spaces.
     * Internally: render the child against a canvas narrowed to
     * {@code canvasCols - leftCols}, then prefix every emitted row
     * with {@code leftCols} spaces. Composes — {@code Padded(Padded(x,
     * 2), 2)} renders the same as {@code Padded(x, 4)}.
     */
    record Padded(Element child, int leftCols) implements Element {}

    /**
     * Decorator. Overrides the style on every span the child emits.
     * Innermost {@code Styled} wins (a styled child of a styled
     * parent gets the inner style). {@code style} uses the same
     * theme string vocabulary as {@code Frames.span(text, style)}
     * ({@code bright_yellow}, {@code grey}, {@code default}, etc.).
     */
    record Styled(Element child, String style) implements Element {}

    /** Section heading row. e.g. "== DOCUMENT ==" with optional right-side annotation. */
    record Header(String title, String rightAnnotation) implements Element {}

    /** Bottom strip carrying mode badge, position info, file-meta. */
    record StatusLine(String mode, String left, String right) implements Element {}

    /** Footer of [K]ey description entries. Client builds visible row + valid_keys. */
    record KeyMenu(List<KeyEntry> entries) implements Element {
        public record KeyEntry(String key, String label) {}
    }

    /** Single-line edit. Client tracks cursor locally; emits field.commit on Enter. */
    record TextField(String id, String label, String value,
                     Integer maxLength, boolean readOnly) implements Element {}

    /** Multi-line modal editor. Client owns live state; server seeds initial content. */
    record Editor(String id, String content, String mode,
                  String syntaxMode, boolean readOnly) implements Element {}

    /** Container; owns focus among focusable children. */
    record Form(String id, List<Element> children, String focusedChildId) implements Element {}
}
