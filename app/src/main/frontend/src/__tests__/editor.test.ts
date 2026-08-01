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

function deps(): RenderDeps & { sent: unknown[] } {
  const sent: unknown[] = [];
  return {
    sent,
    sendMessage: (msg: unknown) => { sent.push(msg); },
    getCurrentTheme: () => "phosphor",
    setStatusBar: () => { /* no-op */ },
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
