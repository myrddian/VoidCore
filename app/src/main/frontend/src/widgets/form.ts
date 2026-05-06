import type { Form } from "./element-types.js";
import { renderTree, type RenderDeps } from "./tree.js";

/**
 * Container hosting focusable children. v1 is purely a render-side
 * passthrough — the server's `focusedChildId` wins; individual
 * focusable widgets pick it up via the `focus` argument. Tab inside
 * a focused field sends focus.move; the server replies with a new tree.
 */
export function renderForm(el: Form, focus: string | null, deps: RenderDeps): HTMLElement {
  const n = document.createElement("div");
  n.className = "widget-form";
  const effectiveFocus = el.focusedChildId ?? focus;
  for (const child of el.children) {
    n.appendChild(renderTree(child, effectiveFocus, deps));
  }
  return n;
}
