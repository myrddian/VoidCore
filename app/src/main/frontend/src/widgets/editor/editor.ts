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

const SNAPSHOT_INTERVAL_MS = 15_000;
const VIEWPORT_LINES = 20;

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

  constructor(
    private readonly el: Editor,
    private readonly deps: RenderDeps,
    private readonly mountNode: HTMLElement,
  ) {
    this.buffer = Buffer.fromString(el.content);
    this.mode = el.readOnly ? "READ_ONLY" : (el.mode as Mode);
    this.initialContent = el.content;
  }

  start(): void {
    document.addEventListener("keydown", this.onKey);
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
    document.removeEventListener("keydown", this.onKey);
    if (this.snapshotTimer != null) clearInterval(this.snapshotTimer);
    this.deps.setStatusBar("");
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
        this.cursor = applyMotion(this.buffer, this.cursor, ev.key);
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

    const moved = applyMotion(this.buffer, this.cursor, key);
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

  private adjustScroll(): void {
    if (this.cursor.line < this.scrollLine) this.scrollLine = this.cursor.line;
    if (this.cursor.line >= this.scrollLine + VIEWPORT_LINES) {
      this.scrollLine = this.cursor.line - VIEWPORT_LINES + 1;
    }
  }

  /**
   * Sync the widget's authoritative state from a freshly-arrived
   * Editor element. Called when renderEditor() reuses an existing
   * widget instance — preserves the live buffer / cursor / scroll
   * (those are client-side truth) but accepts the server's mode and
   * readOnly fields (those are server-side state-machine truth).
   *
   * If we're already in INSERT or COMMAND mode (client-side only),
   * we drop back to whatever the server says — INSERT/COMMAND are
   * transient sub-states of NORMAL and the server transitioning the
   * editor out from under them is a hard reset.
   */
  updateFromServer(el: Editor): void {
    this.mode = el.readOnly ? "READ_ONLY" : (el.mode as Mode);
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
    };
    this.mountNode.replaceChildren(paintEditor(state));
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
