import { describe, it, expect } from "vitest";
import { Buffer } from "../buffer.js";
import { applyEdit } from "../edits.js";

describe("applyEdit", () => {

  it("x deletes char under cursor", () => {
    const b = Buffer.fromString("abc");
    const r = applyEdit(b, { line: 0, col: 1 }, "x");
    expect(b.getLine(0)).toBe("ac");
    expect(r.cursor).toEqual({ line: 0, col: 1 });
  });

  it("X deletes char before cursor", () => {
    const b = Buffer.fromString("abc");
    const r = applyEdit(b, { line: 0, col: 1 }, "X");
    expect(b.getLine(0)).toBe("bc");
    expect(r.cursor).toEqual({ line: 0, col: 0 });
  });

  it("dd deletes the current line", () => {
    const b = Buffer.fromString("a\nb\nc");
    applyEdit(b, { line: 1, col: 0 }, "dd");
    expect(b.toString()).toBe("a\nc");
  });

  it("D deletes from cursor to end of line", () => {
    const b = Buffer.fromString("hello world");
    applyEdit(b, { line: 0, col: 5 }, "D");
    expect(b.getLine(0)).toBe("hello");
  });

  it("J joins line below into current with a space separator", () => {
    const b = Buffer.fromString("hello\nworld");
    applyEdit(b, { line: 0, col: 0 }, "J");
    expect(b.toString()).toBe("hello world");
  });

  it("o opens new line below; cursor lands there at col 0", () => {
    const b = Buffer.fromString("a\nc");
    const r = applyEdit(b, { line: 0, col: 0 }, "o");
    expect(b.toString()).toBe("a\n\nc");
    expect(r.cursor).toEqual({ line: 1, col: 0 });
    expect(r.modeOverride).toBe("INSERT");
  });

  it("O opens new line above", () => {
    const b = Buffer.fromString("a\nc");
    const r = applyEdit(b, { line: 1, col: 0 }, "O");
    expect(b.toString()).toBe("a\n\nc");
    expect(r.cursor).toEqual({ line: 1, col: 0 });
    expect(r.modeOverride).toBe("INSERT");
  });

  it("i / a / I / A switch to INSERT with adjusted cursor", () => {
    const b = Buffer.fromString("hello");
    expect(applyEdit(b, { line: 0, col: 0 }, "i").modeOverride).toBe("INSERT");
    expect(applyEdit(b, { line: 0, col: 0 }, "a").cursor.col).toBe(1);
    expect(applyEdit(b, { line: 0, col: 3 }, "I").cursor.col).toBe(0);
    expect(applyEdit(b, { line: 0, col: 0 }, "A").cursor.col).toBe(5);
  });
});
