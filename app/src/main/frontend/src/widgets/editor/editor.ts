import type { Editor } from "../element-types.js";
import type { RenderDeps } from "../tree.js";
import { Buffer } from "./buffer.js";
import { type Cursor, clampCursor } from "./cursor.js";
import { transitionFromKey, type Mode } from "./modes.js";
import { applyMotion } from "./motions.js";
import { applyEdit } from "./edits.js";
import { UndoRing } from "./undo.js";
import { parseCommand } from "./command-line.js";
import { paintEditor, type RenderState } from "./render.js";
import { editorViewportLines } from "./viewport-lines.js";

const SNAPSHOT_INTERVAL_MS = 15_000;

class EditorWidget {

  private buffer: Buffer;
  private cursor: Cursor = { line: 0, col: 0 };
  private mode: Mode;
  private scrollLine = 0;
  private commandLine = "";
  private dirty = false;
  private readonly initialContent: string;
  private readonly undo = new UndoRing();
  private snapshotTimer: number | null = null;

  /**
   * The editor's key source (ADR-036). A hidden-but-focusable element that
   * holds focus while the editor is active; keys are read from it rather
   * than from `document`. `input.ts` also listens on `document` and nothing
   * coordinates the two — focus is what keeps them apart.
   *
   * A textarea rather than an input: it is the conventional host for IME
   * composition and multi-line paste. Its own value is never used — every
   * handled path calls preventDefault, so it stays empty.
   */
  private readonly keyInput: HTMLTextAreaElement;

  /**
   * The painted buffer. Repaints replace *this* node's children, never
   * mountNode's — re-creating the key input on every keystroke would drop
   * focus and make IME composition impossible.
   */
  private readonly paintNode: HTMLElement;

  /**
   * The server's last *declaration* for this widget. Compared on reuse to
   * tell a genuine server-side change from a bare re-render — see
   * {@link updateFromServer}.
   */
  private lastServerMode: string;
  private lastServerReadOnly: boolean;
  private lastServerContent: string;

  constructor(
    private readonly el: Editor,
    private readonly deps: RenderDeps,
    private readonly mountNode: HTMLElement,
  ) {
    this.buffer = Buffer.fromString(el.content);
    this.mode = el.readOnly ? "READ_ONLY" : (el.mode as Mode);
    this.initialContent = el.content;
    this.lastServerMode = el.mode;
    this.lastServerReadOnly = el.readOnly;
    this.lastServerContent = el.content;

    this.keyInput = document.createElement("textarea");
    this.keyInput.className = "widget-editor-key-input";
    this.keyInput.rows = 1;
    this.keyInput.spellcheck = false;
    this.keyInput.autocapitalize = "off";
    this.keyInput.autocomplete = "off";
    this.keyInput.setAttribute("autocorrect", "off");
    this.keyInput.setAttribute("aria-label", "editor input");

    this.paintNode = document.createElement("div");
    this.paintNode.className = "widget-editor-paint";

    this.mountNode.append(this.keyInput, this.paintNode);
  }

  start(): void {
    this.keyInput.addEventListener("keydown", this.onKey);
    this.focusKeyInput();
    this.snapshotTimer = window.setInterval(
      () => {
        if (!this.dirty) return;          // skip clean buffers
        this.deps.sendMessage({
          type: "editor.snapshot",
          widget_id: this.el.id,
          content: this.buffer.toString(),
        });
      },
      SNAPSHOT_INTERVAL_MS,
    );
    this.repaint();
  }

  stop(): void {
    this.keyInput.removeEventListener("keydown", this.onKey);
    if (document.activeElement === this.keyInput) this.keyInput.blur();
    if (this.snapshotTimer != null) clearInterval(this.snapshotTimer);
    this.deps.setStatusBar("");
  }

  /**
   * Take focus, but never steal it. If the user is in another field —
   * a TextField on the same screen — leave them alone. Called on start,
   * after each repaint, and once the subtree is attached to the document
   * (see {@link focusActiveEditor}): `.focus()` on a detached node is a
   * no-op, and re-parenting a focused node blurs it in some browsers.
   */
  focusKeyInput(): void {
    const active = document.activeElement;
    if (active === this.keyInput) return;
    if (active && active !== document.body && active.tagName !== "HTML") return;
    this.keyInput.focus();
  }

  private onKey = (ev: KeyboardEvent): void => {
    if (ev.metaKey) return;
    if (ev.ctrlKey && !["d", "u", "r"].includes(ev.key.toLowerCase())) return;

    const key = ev.ctrlKey ? "Ctrl-" + ev.key.toLowerCase() : ev.key;

    if (this.mode === "COMMAND") {
      ev.preventDefault();
      if (ev.key === "Enter")     { this.runCommand(); return; }
      if (ev.key === "Escape")    { this.commandLine = ""; this.mode = "NORMAL"; this.repaint(); return; }
      if (ev.key === "Backspace") { this.commandLine = this.commandLine.slice(0, -1); this.repaint(); return; }
      if (ev.key.length === 1)    { this.commandLine += ev.key; this.repaint(); }
      return;
    }

    if (this.mode === "INSERT") {
      ev.preventDefault();
      if (ev.key === "Escape")    { this.mode = "NORMAL"; this.repaint(); return; }
      if (ev.key === "Backspace") {
        this.undo.snapshot(this.buffer);
        if (this.cursor.col > 0) {
          this.buffer.deleteChar(this.cursor.line, this.cursor.col - 1);
          this.cursor = { line: this.cursor.line, col: this.cursor.col - 1 };
        } else if (this.cursor.line > 0) {
          const prevLen = this.buffer.getLine(this.cursor.line - 1).length;
          this.buffer.joinLines(this.cursor.line - 1);
          this.cursor = { line: this.cursor.line - 1, col: prevLen };
        }
        this.dirty = true;
        this.repaint();
        return;
      }
      if (ev.key === "Enter") {
        this.undo.snapshot(this.buffer);
        this.buffer.splitLine(this.cursor.line, this.cursor.col);
        this.cursor = { line: this.cursor.line + 1, col: 0 };
        this.dirty = true;
        this.repaint();
        return;
      }
      if (ev.key.startsWith("Arrow") || ev.key === "Home" || ev.key === "End"
          || ev.key === "PageUp" || ev.key === "PageDown") {
        this.cursor = applyMotion(this.buffer, this.cursor, ev.key, this.viewportLines());
        this.adjustScroll();
        this.repaint();
        return;
      }
      if (ev.key.length === 1) {
        this.undo.snapshot(this.buffer);
        this.buffer.insertChar(this.cursor.line, this.cursor.col, ev.key);
        this.cursor = { line: this.cursor.line, col: this.cursor.col + 1 };
        this.dirty = true;
        this.repaint();
      }
      return;
    }

    // NORMAL or READ_ONLY
    ev.preventDefault();

    // Esc — universal "back to safe state" gesture (BBS convention).
    // READ_ONLY: nothing to save, always exit.
    // NORMAL: server checks dirty (snapshot present); clean pops, dirty notifies.
    if (ev.key === "Escape") {
      this.deps.sendMessage({ type: "editor.cancel",
                              widget_id: this.el.id, force: false });
      return;
    }

    const newMode = transitionFromKey(this.mode, ev.key);
    if (newMode !== this.mode) {
      if (newMode === "INSERT") {
        this.undo.snapshot(this.buffer);
        const r = applyEdit(this.buffer, this.cursor, ev.key);
        this.cursor = r.cursor;
        this.dirty = this.dirty || /[oO]/.test(ev.key);
      } else if (newMode === "COMMAND") {
        this.commandLine = "";
      }
      this.mode = newMode;
      this.repaint();
      return;
    }

    if (this.mode === "NORMAL" && ev.key === "u") {
      if (this.undo.undo(this.buffer)) {
        this.cursor = clampCursor(this.buffer, this.cursor);
        this.repaint();
      }
      return;
    }
    if (this.mode === "NORMAL" && key === "Ctrl-r") {
      if (this.undo.redo(this.buffer)) {
        this.cursor = clampCursor(this.buffer, this.cursor);
        this.repaint();
      }
      return;
    }

    if (this.mode === "NORMAL" && /^[xXJD]$|^d$/.test(ev.key)) {
      this.undo.snapshot(this.buffer);
      const r = applyEdit(this.buffer, this.cursor, ev.key === "d" ? "dd" : ev.key);
      this.cursor = r.cursor;
      this.dirty = true;
      this.repaint();
      return;
    }

    const moved = applyMotion(this.buffer, this.cursor, key, this.viewportLines());
    if (moved !== this.cursor) {
      this.cursor = moved;
      this.adjustScroll();
      this.repaint();
    }
  };

  private runCommand(): void {
    const cmd = parseCommand(":" + this.commandLine);
    this.commandLine = "";
    const prevMode = this.mode;
    this.mode = "NORMAL";
    switch (cmd.kind) {
      case "save":
        this.deps.sendMessage({ type: "editor.commit", widget_id: this.el.id,
                                content: this.buffer.toString(), action: "save" });
        this.dirty = false;
        break;
      case "save_quit":
        this.deps.sendMessage({ type: "editor.commit", widget_id: this.el.id,
                                content: this.buffer.toString(), action: "save_quit" });
        this.dirty = false;
        break;
      case "quit":
        this.deps.sendMessage({ type: "editor.cancel", widget_id: this.el.id, force: !!cmd.force });
        break;
      case "reload":
        this.buffer = Buffer.fromString(this.initialContent);
        this.cursor = { line: 0, col: 0 };
        this.dirty = false;
        break;
      case "toggle_ro":
        this.mode = prevMode === "READ_ONLY" ? "NORMAL" : "READ_ONLY";
        break;
      case "unknown":
        break;
    }
    this.repaint();
  }

  /** Editor height in buffer lines, from the reported client viewport. */
  private viewportLines(): number {
    return editorViewportLines(this.deps.getViewportRows?.() ?? null);
  }

  private adjustScroll(): void {
    if (this.cursor.line < this.scrollLine) this.scrollLine = this.cursor.line;
    const lines = this.viewportLines();
    if (this.cursor.line >= this.scrollLine + lines) {
      this.scrollLine = this.cursor.line - lines + 1;
    }
  }

  /**
   * Sync the widget's authoritative state from a freshly-arrived
   * Editor element. Called when renderEditor() reuses an existing
   * widget instance — preserves the live buffer / cursor / scroll
   * (those are client-side truth).
   *
   * Mode is only taken from the server when the server's *declaration*
   * actually changed. A screen re-sending its tree unchanged — which is
   * what a `viewport.resize` repaint is — must not disturb a live edit;
   * previously any repaint dropped the user out of INSERT or COMMAND,
   * so on mobile the on-screen keyboard opening would eject them.
   *
   * When the declaration does change (readOnly flips, or the screen
   * moves the editor to a different mode) that remains a hard reset:
   * INSERT and COMMAND are transient sub-states of NORMAL, and the
   * server transitioning the editor out from under them wins.
   */
  updateFromServer(el: Editor): void {
    const declarationChanged =
         el.mode !== this.lastServerMode
      || el.readOnly !== this.lastServerReadOnly
      || el.content !== this.lastServerContent;

    this.lastServerMode = el.mode;
    this.lastServerReadOnly = el.readOnly;
    this.lastServerContent = el.content;

    if (declarationChanged) {
      this.mode = el.readOnly ? "READ_ONLY" : (el.mode as Mode);
    }
    this.repaint();
  }

  private repaint(): void {
    const state: RenderState = {
      buffer: this.buffer,
      cursor: this.cursor,
      mode: this.mode,
      scrollLine: this.scrollLine,
      syntaxMode: this.el.syntaxMode === "markdown" ? "markdown" : "plain",
      commandLine: this.commandLine,
      dirty: this.dirty,
      viewportLines: this.viewportLines(),
    };
    this.paintNode.replaceChildren(paintEditor(state));
    this.focusKeyInput();
    // Push mode + position into the global info: bar.
    let bar: string;
    if (this.mode === "COMMAND") {
      bar = `[COMMAND] :${this.commandLine}_`;
    } else if (this.mode === "READ_ONLY") {
      const hint = this.el.readOnly ? "" : "  ·  press E to edit";
      bar = `[READ-ONLY]  L ${this.cursor.line + 1}  C ${this.cursor.col + 1}${hint}`;
    } else {
      bar = `[${this.mode}]  L ${this.cursor.line + 1}  C ${this.cursor.col + 1}`
          + (this.dirty ? "  ·  modified" : "");
    }
    this.deps.setStatusBar(bar);
  }
}

let activeEditor: { widget: EditorWidget; node: HTMLElement; id: string } | null = null;

export function stopActiveEditor(): void {
  if (activeEditor) {
    activeEditor.widget.stop();
    activeEditor = null;
  }
}

export function getActiveEditorId(): string | null {
  return activeEditor ? activeEditor.id : null;
}

/**
 * Give the live editor its focus back once the rendered tree is attached to
 * the document. `renderEditor` runs while the subtree is still detached —
 * the caller attaches it afterwards — and `.focus()` on a detached element
 * does nothing. Call this after the attaching `replaceChildren`.
 *
 * No-op when there is no active editor, and it will not steal focus from
 * another field.
 */
export function focusActiveEditor(): void {
  activeEditor?.widget.focusKeyInput();
}

export function renderEditor(el: Editor, _focus: string | null, deps: RenderDeps): HTMLElement {
  if (activeEditor && activeEditor.id === el.id) {
    // Reuse: keep the live buffer/cursor/scroll (client-side truth),
    // but sync mode + readOnly from the server (state-machine truth).
    activeEditor.widget.updateFromServer(el);
    return activeEditor.node;
  }
  if (activeEditor) activeEditor.widget.stop();
  const node = document.createElement("div");
  node.className = "widget-editor-mount";
  const widget = new EditorWidget(el, deps, node);
  widget.start();
  activeEditor = { widget, node, id: el.id };
  return node;
}
