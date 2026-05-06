import type { StatusLine } from "./element-types.js";

/**
 * Bottom strip with persistent mode badge + position info + file metadata.
 * Mode badge gets its own CSS class (mode-{lowercase}) so the four themes
 * can colour-code each mode (Phase E.2 wires the palettes).
 */
export function renderStatusLine(el: StatusLine): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-status-line";
  const badge = document.createElement("span");
  badge.className = `widget-status-mode mode-${el.mode.toLowerCase().replace(/[^a-z]/g, "-")}`;
  badge.textContent = `[${el.mode}]`;
  const left = document.createElement("span");
  left.className = "widget-status-left";
  left.textContent = el.left;
  const right = document.createElement("span");
  right.className = "widget-status-right";
  right.textContent = el.right;
  node.appendChild(badge);
  node.appendChild(left);
  node.appendChild(right);
  return node;
}
