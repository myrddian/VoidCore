import type { KeyMenu } from "./element-types.js";

/**
 * Renders the [F]ile [B]ulletins ... footer pattern. The key letter
 * is wrapped in <span class="widget-key-menu-key">, the label in
 * <span class="widget-key-menu-label">. Server still drives valid_keys
 * via InputPrompt — this widget is purely visual at v1.
 */
export function renderKeyMenu(el: KeyMenu): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-key-menu";
  for (let i = 0; i < el.entries.length; i++) {
    if (i > 0) {
      const sep = document.createElement("span");
      sep.className = "widget-key-menu-sep";
      sep.textContent = "  ";
      node.appendChild(sep);
    }
    const entry = el.entries[i]!;
    const wrap = document.createElement("span");
    wrap.className = "widget-key-menu-entry";
    const open = document.createElement("span");
    open.className = "widget-key-menu-bracket";
    open.textContent = "[";
    const k = document.createElement("span");
    k.className = "widget-key-menu-key";
    k.textContent = entry.key;
    const close = document.createElement("span");
    close.className = "widget-key-menu-bracket";
    close.textContent = "]";
    const label = document.createElement("span");
    label.className = "widget-key-menu-label";
    label.textContent = ` ${entry.label}`;
    wrap.append(open, k, close, label);
    node.appendChild(wrap);
  }
  return node;
}
