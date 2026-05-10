/**
 * Walks an Element tree and produces DOM. Dispatches per element kind.
 * Per-widget renderers live in their own files; this module only owns
 * dispatch + container layouts (VStack, Padded, Styled).
 */
import type { Element, Shell, VStack, Padded, Styled, AnsiBlock, AnsiSpan } from "./element-types.js";
import { renderHeader } from "./header.js";
import { renderStatusLine } from "./status-line.js";
import { renderKeyMenu } from "./key-menu.js";
import { renderTextField } from "./text-field.js";
import { renderForm } from "./form.js";
import { renderEditor } from "./editor/editor.js";

export interface RenderDeps {
  sendMessage: (msg: unknown) => void;
  getCurrentTheme: () => string;
  setStatusBar: (text: string) => void;
}

export function renderTree(el: Element, focus: string | null, deps: RenderDeps): HTMLElement {
  switch (el.kind) {
    case "shell":      return renderShell(el, focus, deps);
    case "vstack":     return renderVStack(el, focus, deps);
    case "padded":     return renderPadded(el, focus, deps);
    case "styled":     return renderStyled(el, focus, deps);
    case "ansiBlock":  return renderAnsiBlock(el);
    case "spacer":     return renderSpacer(el.rows);
    case "rule":       return renderRule();
    case "text":       return renderTextEl(el.content, el.style);
    case "para":       return renderParaEl(el.content, el.style);
    case "header":     return renderHeader(el);
    case "statusLine": return renderStatusLine(el);
    case "keyMenu":    return renderKeyMenu(el);
    case "textField":  return renderTextField(el, focus, deps);
    case "editor":     return renderEditor(el, focus, deps);
    case "form":       return renderForm(el, focus, deps);
    default: {
      const node = document.createElement("div");
      node.className = "widget-unknown";
      node.textContent = `[unknown widget: ${(el as { kind?: string }).kind}]`;
      return node;
    }
  }
}

function renderShell(shell: Shell, focus: string | null, deps: RenderDeps): HTMLElement {
  const node = document.createElement("div");
  node.className = `widget-shell variant-${shell.variant || "default"}`;

  if (shell.top) {
    const top = document.createElement("div");
    top.className = "widget-shell-top";
    top.appendChild(renderTree(shell.top, focus, deps));
    node.appendChild(top);
  }

  const middle = document.createElement("div");
  middle.className = "widget-shell-middle";

  if (shell.left) {
    const left = document.createElement("div");
    left.className = "widget-shell-left";
    left.appendChild(renderTree(shell.left, focus, deps));
    middle.appendChild(left);
  }

  const body = document.createElement("div");
  body.className = "widget-shell-body";
  body.appendChild(renderTree(shell.body, focus, deps));
  middle.appendChild(body);

  if (shell.right) {
    const right = document.createElement("div");
    right.className = "widget-shell-right";
    right.appendChild(renderTree(shell.right, focus, deps));
    middle.appendChild(right);
  }

  node.appendChild(middle);

  if (shell.bottom) {
    const bottom = document.createElement("div");
    bottom.className = "widget-shell-bottom";
    bottom.appendChild(renderTree(shell.bottom, focus, deps));
    node.appendChild(bottom);
  }

  return node;
}

function renderVStack(v: VStack, focus: string | null, deps: RenderDeps): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-vstack";
  for (let i = 0; i < v.children.length; i++) {
    if (i > 0 && v.gap > 0) {
      node.appendChild(renderSpacer(v.gap));
    }
    node.appendChild(renderTree(v.children[i]!, focus, deps));
  }
  return node;
}

function renderPadded(p: Padded, focus: string | null, deps: RenderDeps): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-padded";
  node.style.paddingLeft = `${p.leftCols}ch`;
  node.appendChild(renderTree(p.child, focus, deps));
  return node;
}

function renderStyled(s: Styled, focus: string | null, deps: RenderDeps): HTMLElement {
  const node = document.createElement("div");
  node.className = `widget-styled style-${s.style}`;
  node.appendChild(renderTree(s.child, focus, deps));
  return node;
}

function renderSpacer(rows: number): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-spacer";
  node.style.height = `${rows}em`;
  return node;
}

function renderAnsiBlock(block: AnsiBlock): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-ansi-block";
  for (const row of block.rows) {
    const line = document.createElement("div");
    line.className = "widget-ansi-line";
    for (const span of row.spans) {
      line.appendChild(renderAnsiSpan(span));
    }
    node.appendChild(line);
  }
  return node;
}

function renderAnsiSpan(span: AnsiSpan): HTMLElement {
  const el = document.createElement("span");
  const classes: string[] = ["span"];
  if (span.fg && span.fg !== "default") classes.push(`fg-${normalise(span.fg)}`);
  if (span.bg && span.bg !== "default") classes.push(`bg-${normalise(span.bg)}`);
  if (span.bold) classes.push("bold");
  el.className = classes.join(" ");
  el.textContent = span.text;
  return el;
}

function renderRule(): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-rule";
  // Drawn via CSS border-top — see theme.css. Avoids char-count
  // overflow on narrow viewports.
  return node;
}

function renderTextEl(content: string, style: string): HTMLElement {
  const node = document.createElement("div");
  node.className = `widget-text style-${style}`;
  node.textContent = content;
  return node;
}

function renderParaEl(content: string, style: string): HTMLElement {
  const node = document.createElement("div");
  node.className = `widget-para style-${style}`;
  node.textContent = content;
  return node;
}

function normalise(name: string): string {
  return name.replace(/_/g, "-");
}
