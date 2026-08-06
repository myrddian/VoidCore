import { beforeEach, describe, expect, it } from "vitest";

import type { ListView } from "../widgets/element-types.js";
import type { RenderDeps } from "../widgets/tree.js";
import { renderList } from "../widgets/list.js";

/**
 * The `list` element — first of the v2 layout vocabulary (ADR-035).
 *
 * The contract worth protecting: the client owns cursor movement and the
 * server hears only a commit. Every "moving the highlight sends nothing"
 * assertion below is that contract.
 */

function listEl(overrides: Partial<ListView> = {}): ListView {
  return {
    kind: "list",
    id: "bases",
    items: [
      { id: "general", label: "General Chatter", secondary: "12 unread" },
      { id: "production", label: "Production / Gear" },
      { id: "releases", label: "Releases" },
      { id: "meta", label: "Meta" },
    ],
    selectedId: null,
    ...overrides,
  };
}

let sent: Array<Record<string, unknown>>;
let viewportRows: number | null;

function deps(): RenderDeps {
  return {
    sendMessage: (msg) => { sent.push(msg as Record<string, unknown>); },
    getCurrentTheme: () => "phosphor",
    setStatusBar: () => { /* no-op */ },
    getViewportRows: () => viewportRows,
  };
}

function mount(el: ListView = listEl()) {
  const node = renderList(el, el.id, deps());
  document.body.replaceChildren(node);
  return node;
}

function press(node: HTMLElement, key: string, shiftKey = false): void {
  node.querySelector<HTMLElement>(".widget-list-key-input")!
    .dispatchEvent(new KeyboardEvent("keydown", { key, shiftKey, bubbles: true, cancelable: true }));
}

function selectedLabel(node: HTMLElement): string | null {
  return node.querySelector(".widget-list-row-selected .widget-list-label")?.textContent ?? null;
}

function visibleLabels(node: HTMLElement): string[] {
  return Array.from(node.querySelectorAll(".widget-list-label")).map((e) => e.textContent ?? "");
}

beforeEach(() => {
  sent = [];
  viewportRows = null;
  document.body.replaceChildren();
});

describe("list widget", () => {
  it("highlights the first item when the server names none", () => {
    expect(selectedLabel(mount())).toBe("General Chatter");
  });

  it("honours the server's initial selection", () => {
    expect(selectedLabel(mount(listEl({ selectedId: "releases" })))).toBe("Releases");
  });

  it("moves the highlight locally without telling the server", () => {
    const node = mount();

    press(node, "ArrowDown");
    press(node, "ArrowDown");

    expect(selectedLabel(node)).toBe("Releases");
    // The whole point of the element: navigation costs no round trip.
    expect(sent).toEqual([]);
  });

  it("accepts vim keys as well as arrows", () => {
    const node = mount();

    press(node, "j");
    expect(selectedLabel(node)).toBe("Production / Gear");
    press(node, "k");
    expect(selectedLabel(node)).toBe("General Chatter");
  });

  it("stops at the ends rather than wrapping", () => {
    const node = mount();

    press(node, "ArrowUp");
    expect(selectedLabel(node)).toBe("General Chatter");

    for (let i = 0; i < 10; i++) press(node, "ArrowDown");
    expect(selectedLabel(node)).toBe("Meta");
    expect(sent).toEqual([]);
  });

  it("sends the item id, not its index, on Enter", () => {
    const node = mount();

    press(node, "ArrowDown");
    press(node, "Enter");

    // Index would break the moment the list reorders between paints.
    expect(sent).toEqual([
      { type: "list.selected", widget_id: "bases", item_id: "production" },
    ]);
  });

  it("reports cancel and focus moves like other widgets", () => {
    const node = mount();

    press(node, "Escape");
    press(node, "Tab", true);

    expect(sent).toEqual([
      { type: "field.cancel", widget_id: "bases" },
      { type: "focus.move", from: "bases", direction: "prev" },
    ]);
  });

  it("renders secondary text only where the item has it", () => {
    const node = mount();
    const secondaries = Array.from(node.querySelectorAll(".widget-list-secondary"));

    expect(secondaries).toHaveLength(1);
    expect(secondaries[0]?.textContent).toBe("12 unread");
  });

  it("windows to the viewport and scrolls the window with the cursor", () => {
    // 12 rows of canvas, less chrome, leaves room for 6 items.
    viewportRows = 12;
    const many = Array.from({ length: 20 }, (_, i) => ({ id: `i${i}`, label: `item ${i}` }));
    const node = mount(listEl({ items: many }));

    expect(visibleLabels(node)).toHaveLength(6);
    expect(visibleLabels(node)[0]).toBe("item 0");

    for (let i = 0; i < 10; i++) press(node, "ArrowDown");

    expect(selectedLabel(node)).toBe("item 10");
    // The window followed rather than the list growing.
    expect(visibleLabels(node)).toHaveLength(6);
    expect(visibleLabels(node)).toContain("item 10");
    expect(visibleLabels(node)).not.toContain("item 0");
    expect(sent).toEqual([]);
  });

  it("survives an empty list without sending anything", () => {
    const node = mount(listEl({ items: [] }));

    press(node, "ArrowDown");
    press(node, "Enter");

    expect(visibleLabels(node)).toEqual([]);
    expect(sent).toEqual([]);
  });
});
