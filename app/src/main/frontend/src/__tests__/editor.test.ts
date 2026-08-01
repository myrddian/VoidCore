import { afterEach, describe, expect, it } from "vitest";

import type { Editor } from "../widgets/element-types.js";
import type { RenderDeps } from "../widgets/tree.js";
import { focusActiveEditor, renderEditor, stopActiveEditor } from "../widgets/editor/editor.js";

/**
 * Editor input ownership — ADR-036.
 *
 * The editor owns a hidden-but-focusable input that holds focus while it is
 * active, and reads keys from that element. It must NOT listen on `document`:
 * `input.ts` also has a document-level keydown listener, and nothing
 * coordinates the two. Before ADR-036 the only thing keeping them apart was
 * the server having sent `input.prompt {mode: "none"}` for the screen.
 */

function editorEl(overrides: Partial<Editor> = {}): Editor {
  return {
    kind: "editor",
    id: "body",
    content: "",
    mode: "INSERT",
    syntaxMode: "plain",
    readOnly: false,
    ...overrides,
  };
}

function deps(): RenderDeps & { sent: unknown[]; status: string[] } {
  const sent: unknown[] = [];
  const status: string[] = [];
  return {
    sent,
    status,
    sendMessage: (msg: unknown) => { sent.push(msg); },
    getCurrentTheme: () => "phosphor",
    setStatusBar: (text: string) => { status.push(text); },
  };
}

/**
 * Mount an editor into the document so focus behaviour is real. Mirrors
 * main.ts's tree path: render (detached) → attach → focus.
 */
function mount(el: Editor = editorEl()) {
  const d = deps();
  const node = renderEditor(el, null, d);
  document.body.replaceChildren(node);
  focusActiveEditor();
  return { node, deps: d };
}

/** The editor's own key-input element. */
function keyInput(node: HTMLElement): HTMLElement | null {
  return node.querySelector<HTMLElement>(".widget-editor-key-input");
}

/** Visible text of the painted buffer. */
function paintedText(node: HTMLElement): string {
  return Array.from(node.querySelectorAll(".widget-editor-line"))
    .map((l) => l.textContent ?? "")
    .join("\n");
}

function press(target: EventTarget, key: string): void {
  target.dispatchEvent(new KeyboardEvent("keydown", {
    key, bubbles: true, cancelable: true,
  }));
}

afterEach(() => {
  stopActiveEditor();
  document.body.replaceChildren();
});

describe("editor input ownership (ADR-036)", () => {
  it("owns a focusable input that holds focus while active", () => {
    const { node } = mount();
    const input = keyInput(node);

    expect(input).not.toBeNull();
    expect(document.activeElement).toBe(input);
  });

  it("handles keys delivered to its own input", () => {
    const { node } = mount();
    const input = keyInput(node)!;

    press(input, "h");
    press(input, "i");

    expect(paintedText(node)).toContain("hi");
  });

  it("ignores keys delivered elsewhere in the document", () => {
    const { node } = mount();
    const before = paintedText(node);

    // input.ts listens on document too. An editor that also listens there
    // double-handles every keystroke — the bug ADR-036 removes.
    press(document.body, "z");

    expect(paintedText(node)).toBe(before);
  });

  it("keeps the same input element, and its focus, across repaints", () => {
    const { node } = mount();
    const input = keyInput(node)!;

    press(input, "a");

    // The paint swaps the rendered buffer, not the input. Re-creating the
    // input on every keystroke would drop focus and break IME composition.
    expect(keyInput(node)).toBe(input);
    expect(document.activeElement).toBe(input);
  });

  it("releases the document and its focus when stopped", () => {
    const { node } = mount();
    const input = keyInput(node)!;

    stopActiveEditor();

    press(input, "q");
    expect(paintedText(node)).not.toContain("q");
  });
});

/**
 * Repaint must not disturb a live edit — ADR-036 follow-up.
 *
 * A screen re-sending its tree unchanged (which is what a viewport.resize
 * repaint is) arrives here as a reuse with an identical Editor element. The
 * user's mode is client-side state; only a genuine server-side change should
 * override it.
 */
describe("editor repaint (ADR-036 follow-up)", () => {
  /** Mode as the widget reports it — the `[MODE]` prefix of the info bar. */
  function modeOf(d: { status: string[] }): string {
    const last = d.status[d.status.length - 1] ?? "";
    return last.slice(0, last.indexOf("]") + 1);
  }

  it("keeps the user's mode when the same element is re-sent", () => {
    // Screen declares NORMAL; the user enters INSERT client-side.
    const { node, deps: d } = mount(editorEl({ mode: "NORMAL" }));
    press(keyInput(node)!, "i");
    expect(modeOf(d)).toBe("[INSERT]");

    // An unchanged re-render — what a resize repaint is — must not yank
    // them out of INSERT.
    renderEditor(editorEl({ mode: "NORMAL" }), null, d);

    expect(modeOf(d)).toBe("[INSERT]");
  });

  it("keeps the live buffer when the same element is re-sent", () => {
    const { node, deps: d } = mount(editorEl({ mode: "NORMAL" }));
    press(keyInput(node)!, "i");
    press(keyInput(node)!, "x");

    renderEditor(editorEl({ mode: "NORMAL" }), null, d);

    expect(paintedText(node)).toContain("x");
  });

  it("still hard-resets when the server actually changes the editor", () => {
    const { node, deps: d } = mount(editorEl({ mode: "NORMAL" }));
    press(keyInput(node)!, "i");
    expect(modeOf(d)).toBe("[INSERT]");

    // readOnly flipping is a real server-side state change, not a re-render.
    renderEditor(editorEl({ mode: "NORMAL", readOnly: true }), null, d);

    expect(modeOf(d)).toBe("[READ-ONLY]");
  });
});
