import type { ListView } from "./element-types.js";
import type { RenderDeps } from "./tree.js";

/**
 * Selectable list.
 *
 * The client owns cursor movement, the highlight, and viewport-local
 * scrolling; the server hears only about a commit, as `list.selected`.
 * Moving the highlight is the most common thing a user does on a list, and
 * in the keystroke-per-frame model every move costs a round trip. Here it
 * costs nothing and the server still learns the outcome.
 *
 * Input arrives through a hidden-but-focusable element rather than a
 * `document` listener, per ADR-036: focus is what keeps this widget and
 * `input.ts` out of each other's way.
 */

/** Rows kept above/below the cursor when scrolling the window. */
const SCROLL_MARGIN = 1;

export function renderList(el: ListView, focus: string | null, deps: RenderDeps): HTMLElement {
  const focused = focus === el.id;

  const node = document.createElement("div");
  node.className = "widget-list" + (focused ? " widget-list-focused" : "");

  const keyInput = document.createElement("input");
  keyInput.className = "widget-list-key-input";
  keyInput.setAttribute("aria-label", "list");
  keyInput.autocomplete = "off";

  const body = document.createElement("div");
  body.className = "widget-list-body";

  node.append(keyInput, body);

  const items = el.items ?? [];
  // Selection travels by item id, never by index: a list that reorders
  // between paints would otherwise commit whatever moved into the slot.
  let cursor = Math.max(0, items.findIndex((i) => i.id === el.selectedId));
  let top = 0;

  /** Rows visible at once — derived from the client viewport, like the editor. */
  function windowSize(): number {
    const rows = deps.getViewportRows?.() ?? null;
    if (rows == null || !Number.isFinite(rows) || rows <= 0) return items.length;
    return Math.max(3, Math.min(items.length, Math.floor(rows) - 6));
  }

  function paint(): void {
    const size = windowSize();
    if (cursor < top + SCROLL_MARGIN) top = Math.max(0, cursor - SCROLL_MARGIN);
    if (cursor >= top + size - SCROLL_MARGIN) top = Math.min(
      Math.max(0, items.length - size), cursor - size + 1 + SCROLL_MARGIN);

    const rows: HTMLElement[] = [];
    for (let i = top; i < Math.min(items.length, top + size); i++) {
      const item = items[i];
      if (!item) continue;
      const row = document.createElement("div");
      row.className = "widget-list-row" + (i === cursor ? " widget-list-row-selected" : "");
      row.setAttribute("data-item-id", item.id);

      const label = document.createElement("span");
      label.className = "widget-list-label";
      label.textContent = item.label;
      row.appendChild(label);

      if (item.secondary) {
        const secondary = document.createElement("span");
        secondary.className = "widget-list-secondary";
        secondary.textContent = item.secondary;
        row.appendChild(secondary);
      }
      rows.push(row);
    }
    body.replaceChildren(...rows);
  }

  function move(delta: number): void {
    if (items.length === 0) return;
    const next = Math.max(0, Math.min(items.length - 1, cursor + delta));
    if (next === cursor) return;
    cursor = next;
    paint();
  }

  keyInput.addEventListener("keydown", (ev: KeyboardEvent) => {
    if (ev.metaKey || ev.ctrlKey) return;
    const size = windowSize();
    switch (ev.key) {
      case "ArrowDown": case "j": ev.preventDefault(); move(1); return;
      case "ArrowUp":   case "k": ev.preventDefault(); move(-1); return;
      case "PageDown":            ev.preventDefault(); move(size); return;
      case "PageUp":              ev.preventDefault(); move(-size); return;
      case "Home":                ev.preventDefault(); move(-items.length); return;
      case "End":                 ev.preventDefault(); move(items.length); return;
      case "Enter": {
        ev.preventDefault();
        const item = items[cursor];
        if (item) {
          deps.sendMessage({ type: "list.selected", widget_id: el.id, item_id: item.id });
        }
        return;
      }
      case "Escape":
        ev.preventDefault();
        deps.sendMessage({ type: "field.cancel", widget_id: el.id });
        return;
      case "Tab":
        ev.preventDefault();
        deps.sendMessage({
          type: "focus.move", from: el.id,
          direction: ev.shiftKey ? "prev" : "next",
        });
        return;
      default:
        return;
    }
  });

  paint();
  if (focused) {
    // .focus() is a no-op while detached; the caller attaches this subtree
    // afterwards, so defer to a microtask (ADR-036 hit the same trap).
    queueMicrotask(() => {
      const active = document.activeElement;
      if (active && active !== document.body && active.tagName !== "HTML") return;
      keyInput.focus();
    });
  }

  return node;
}
