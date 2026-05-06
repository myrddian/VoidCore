import type { Buffer } from "./buffer.js";
import type { Cursor } from "./cursor.js";
import type { Mode } from "./modes.js";
import { tokeniseMarkdown, type Token } from "./markdown.js";

const VIEWPORT_LINES = 20;

export interface RenderState {
  buffer: Buffer;
  cursor: Cursor;
  mode: Mode;
  scrollLine: number;
  syntaxMode: "markdown" | "plain";
  commandLine: string;
  dirty: boolean;
}

/**
 * Build the editor's DOM subtree from current state. Caller replaces
 * the previous subtree via parent.replaceChildren(node).
 *
 * Layout:
 *   .widget-editor                — root, mode-{lower} class for theming
 *     .widget-editor-body         — visible window of buffer
 *       .widget-editor-line       — one line, .cursor-line if focused
 *         .widget-editor-line-num — gutter line number
 *         .tok-{kind}             — markdown token spans
 *         .widget-editor-cursor   — cursor cell (highlighted character)
 *     .widget-editor-cmd          — present in COMMAND mode only
 *
 * Mode + position are pushed into the global info: status bar via
 * deps.setStatusBar() on every repaint — not rendered inline here.
 */
export function paintEditor(state: RenderState): HTMLElement {
  const root = document.createElement("div");
  root.className = `widget-editor mode-${state.mode.toLowerCase()}`;

  const body = document.createElement("div");
  body.className = "widget-editor-body";
  const start = Math.max(0, state.scrollLine);
  const end = Math.min(state.buffer.lineCount(), start + VIEWPORT_LINES);
  let fenceState = false;
  for (let i = 0; i < start; i++) {
    fenceState = tokeniseMarkdown(state.buffer.getLine(i), fenceState).fenceState;
  }
  for (let i = start; i < end; i++) {
    const lineEl = document.createElement("div");
    lineEl.className = "widget-editor-line";
    if (i === state.cursor.line) lineEl.classList.add("cursor-line");

    const num = document.createElement("span");
    num.className = "widget-editor-line-num";
    num.textContent = String(i + 1).padStart(3, " ") + " ";
    lineEl.appendChild(num);

    const lineText = state.buffer.getLine(i);
    const tokens = state.syntaxMode === "markdown"
        ? tokeniseMarkdown(lineText, fenceState)
        : { tokens: [{ kind: "plain", text: lineText } as Token], fenceState };
    fenceState = tokens.fenceState;

    appendTokensWithCursor(lineEl, tokens.tokens,
                           i === state.cursor.line ? state.cursor.col : -1);
    body.appendChild(lineEl);
  }
  root.appendChild(body);

  return root;
}

function appendTokensWithCursor(parent: HTMLElement, tokens: Token[], cursorCol: number): void {
  let col = 0;
  for (const t of tokens) {
    const span = document.createElement("span");
    span.className = `tok-${t.kind}`;
    if (cursorCol >= col && cursorCol < col + t.text.length) {
      const offset = cursorCol - col;
      span.append(document.createTextNode(t.text.slice(0, offset)));
      const c = document.createElement("span");
      c.className = "widget-editor-cursor";
      c.textContent = t.text[offset] || " ";
      span.appendChild(c);
      span.append(document.createTextNode(t.text.slice(offset + 1)));
      parent.appendChild(span);
      col += t.text.length;
      cursorCol = -1;
      continue;
    }
    span.textContent = t.text;
    parent.appendChild(span);
    col += t.text.length;
  }
  if (cursorCol === col) {
    const c = document.createElement("span");
    c.className = "widget-editor-cursor";
    c.textContent = " ";
    parent.appendChild(c);
  }
}
