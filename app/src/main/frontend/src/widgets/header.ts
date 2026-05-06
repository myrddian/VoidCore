import type { Header } from "./element-types.js";

export function renderHeader(el: Header): HTMLElement {
  const node = document.createElement("div");
  node.className = "widget-header";
  const title = document.createElement("span");
  title.className = "widget-header-title";
  title.textContent = `== ${el.title} ==`;
  node.appendChild(title);
  if (el.rightAnnotation) {
    const right = document.createElement("span");
    right.className = "widget-header-right";
    right.textContent = el.rightAnnotation;
    node.appendChild(right);
  }
  return node;
}
