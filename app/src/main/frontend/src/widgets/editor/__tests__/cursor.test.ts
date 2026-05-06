import { describe, it, expect } from "vitest";
import { Buffer } from "../buffer.js";
import { clampCursor } from "../cursor.js";

describe("clampCursor", () => {
  it("clamps line to [0, lineCount-1]", () => {
    const b = Buffer.fromString("a\nb\nc");
    expect(clampCursor(b, { line: -5, col: 0 })).toEqual({ line: 0, col: 0 });
    expect(clampCursor(b, { line: 99, col: 0 })).toEqual({ line: 2, col: 0 });
  });

  it("clamps col to [0, line.length]", () => {
    const b = Buffer.fromString("hello");
    expect(clampCursor(b, { line: 0, col: -3 })).toEqual({ line: 0, col: 0 });
    expect(clampCursor(b, { line: 0, col: 99 })).toEqual({ line: 0, col: 5 });
  });
});
