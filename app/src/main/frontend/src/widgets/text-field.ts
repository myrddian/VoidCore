import type { TextField } from "./element-types.js";
import type { RenderDeps } from "./tree.js";

/**
 * Single-line edit widget. Local cursor + buffer; Enter sends a
 * field.commit upstream; Tab / Shift-Tab send focus.move; Escape sends
 * field.cancel so the owning screen can back out cleanly.
 *
 * Cursor is rendered as a ▏ character inserted at the cursor position
 * — preserves the BBS feel without inverse-video cursor tricks.
 */
export function renderTextField(el: TextField, focus: string | null, deps: RenderDeps): HTMLElement {
  const focused = focus === el.id;
  const node = document.createElement("div");
  node.className = "widget-text-field"
    + (focused ? " widget-text-field-focused" : "")
    + (el.readOnly ? " widget-text-field-readonly" : "");
  node.tabIndex = focused ? 0 : -1;

  const label = document.createElement("span");
  label.className = "widget-text-field-label";
  label.textContent = el.label + " ";

  const value = document.createElement("span");
  value.className = "widget-text-field-value";
  value.textContent = el.value;

  node.append(label, value);

  // Local state — cursor + buffer. Reseeded on every render.
  let buffer = el.value;
  let cursor = buffer.length;

  if (focused && !el.readOnly) {
    node.addEventListener("keydown", (ev: KeyboardEvent) => {
      if (ev.metaKey || ev.ctrlKey) return;

      if (ev.key === "Enter") {
        ev.preventDefault();
        deps.sendMessage({ type: "field.commit", widget_id: el.id, value: buffer });
        return;
      }
      if (ev.key === "Tab") {
        ev.preventDefault();
        deps.sendMessage({
          type: "focus.move", from: el.id,
          direction: ev.shiftKey ? "prev" : "next",
        });
        return;
      }
      if (ev.key === "Escape") {
        ev.preventDefault();
        deps.sendMessage({ type: "field.cancel", widget_id: el.id });
        return;
      }
      if (ev.key === "Backspace") {
        ev.preventDefault();
        if (cursor > 0) {
          buffer = buffer.slice(0, cursor - 1) + buffer.slice(cursor);
          cursor--;
          repaintValue();
        }
        return;
      }
      if (ev.key === "ArrowLeft") {
        ev.preventDefault();
        cursor = Math.max(0, cursor - 1);
        repaintValue();
        return;
      }
      if (ev.key === "ArrowRight") {
        ev.preventDefault();
        cursor = Math.min(buffer.length, cursor + 1);
        repaintValue();
        return;
      }
      if (ev.key.length === 1) {
        if (el.maxLength != null && buffer.length >= el.maxLength) return;
        ev.preventDefault();
        buffer = buffer.slice(0, cursor) + ev.key + buffer.slice(cursor);
        cursor++;
        repaintValue();
      }
    });
    setTimeout(() => node.focus(), 0);
    // Push a hint into the global status bar so the user knows what
    // keys are available while a TextField has focus. Cleared when the
    // next InputPrompt or editor.setStatusBar arrives (i.e. when the
    // server transitions back to a different state).
    const labelText = el.label.replace(/:\s*$/, "").trim();
    deps.setStatusBar(`${labelText}:  [Enter] commit   [Esc] cancel   [Tab] back`);
  }

  function repaintValue(): void {
    const before = document.createTextNode(buffer.slice(0, cursor));
    const after  = document.createTextNode(buffer.slice(cursor));
    if (focused && !el.readOnly) {
      const c = document.createElement("span");
      c.className = "widget-text-field-cursor";
      c.textContent = "▏";
      value.replaceChildren(before, c, after);
    } else {
      value.replaceChildren(before, after);
    }
  }
  repaintValue();

  return node;
}
