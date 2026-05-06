import { describe, it, expect } from "vitest";
import { Buffer } from "../buffer.js";
import { UndoRing } from "../undo.js";

describe("UndoRing — single-step", () => {

  it("undo restores the last snapshot", () => {
    const b = Buffer.fromString("a\nb\nc");
    const ring = new UndoRing();
    ring.snapshot(b);
    b.deleteLine(1);
    ring.undo(b);
    expect(b.toString()).toBe("a\nb\nc");
  });

  it("redo re-applies the undone change", () => {
    const b = Buffer.fromString("hello");
    const ring = new UndoRing();
    ring.snapshot(b);
    b.insertChar(0, 5, "!");
    ring.undo(b);
    expect(b.toString()).toBe("hello");
    ring.redo(b);
    expect(b.toString()).toBe("hello!");
  });

  it("two snapshots only retain the most recent (single-step ring)", () => {
    const b = Buffer.fromString("a");
    const ring = new UndoRing();
    ring.snapshot(b);
    b.insertChar(0, 1, "b");
    ring.snapshot(b);
    b.insertChar(0, 2, "c");
    ring.undo(b);
    expect(b.toString()).toBe("ab");
  });
});
