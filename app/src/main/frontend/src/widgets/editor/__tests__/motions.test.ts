import { describe, it, expect } from "vitest";
import { Buffer } from "../buffer.js";
import { applyMotion } from "../motions.js";

const buf = Buffer.fromString("hello world\nfoo bar baz\nthird line\nfourth\nfifth");

describe("applyMotion", () => {

  it("h decrements col, clamped at 0", () => {
    expect(applyMotion(buf, { line: 0, col: 3 }, "h")).toEqual({ line: 0, col: 2 });
    expect(applyMotion(buf, { line: 0, col: 0 }, "h")).toEqual({ line: 0, col: 0 });
  });

  it("l increments col, clamped at line length", () => {
    expect(applyMotion(buf, { line: 0, col: 10 }, "l")).toEqual({ line: 0, col: 11 });
    expect(applyMotion(buf, { line: 0, col: 11 }, "l")).toEqual({ line: 0, col: 11 });
  });

  it("j moves down, k moves up", () => {
    expect(applyMotion(buf, { line: 1, col: 2 }, "j")).toEqual({ line: 2, col: 2 });
    expect(applyMotion(buf, { line: 1, col: 2 }, "k")).toEqual({ line: 0, col: 2 });
  });

  it("ArrowKeys map to hjkl", () => {
    expect(applyMotion(buf, { line: 1, col: 1 }, "ArrowLeft")).toEqual({ line: 1, col: 0 });
    expect(applyMotion(buf, { line: 1, col: 1 }, "ArrowRight")).toEqual({ line: 1, col: 2 });
    expect(applyMotion(buf, { line: 1, col: 1 }, "ArrowUp")).toEqual({ line: 0, col: 1 });
    expect(applyMotion(buf, { line: 1, col: 1 }, "ArrowDown")).toEqual({ line: 2, col: 1 });
  });

  it("0 jumps to col 0; $ to end of line", () => {
    expect(applyMotion(buf, { line: 0, col: 5 }, "0")).toEqual({ line: 0, col: 0 });
    expect(applyMotion(buf, { line: 0, col: 5 }, "$")).toEqual({ line: 0, col: 11 });
  });

  it("^ jumps to first non-whitespace", () => {
    const b = Buffer.fromString("    hello");
    expect(applyMotion(b, { line: 0, col: 8 }, "^")).toEqual({ line: 0, col: 4 });
  });

  it("gg jumps to top, G to last line", () => {
    expect(applyMotion(buf, { line: 3, col: 2 }, "gg")).toEqual({ line: 0, col: 0 });
    expect(applyMotion(buf, { line: 0, col: 0 }, "G")).toEqual({ line: 4, col: 0 });
  });

  it("w jumps to start of next word", () => {
    expect(applyMotion(buf, { line: 0, col: 0 }, "w")).toEqual({ line: 0, col: 6 });
  });

  it("b jumps to start of previous word", () => {
    expect(applyMotion(buf, { line: 0, col: 6 }, "b")).toEqual({ line: 0, col: 0 });
  });

  it("PgDn moves a viewport down (default 20 lines)", () => {
    const big = Buffer.fromString(Array.from({ length: 50 }, (_, i) => `line${i}`).join("\n"));
    expect(applyMotion(big, { line: 0, col: 0 }, "PageDown")).toEqual({ line: 20, col: 0 });
  });

  it("Ctrl-d / Ctrl-u half viewport", () => {
    const big = Buffer.fromString(Array.from({ length: 50 }, () => "x").join("\n"));
    expect(applyMotion(big, { line: 0, col: 0 }, "Ctrl-d")).toEqual({ line: 10, col: 0 });
    expect(applyMotion(big, { line: 30, col: 0 }, "Ctrl-u")).toEqual({ line: 20, col: 0 });
  });
});
