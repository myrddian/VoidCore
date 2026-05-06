import { describe, it, expect } from "vitest";
import { Buffer } from "../buffer.js";

describe("Buffer", () => {

  it("constructs from a string with newlines", () => {
    const b = Buffer.fromString("a\nbc\nd");
    expect(b.lineCount()).toBe(3);
    expect(b.getLine(0)).toBe("a");
    expect(b.getLine(2)).toBe("d");
  });

  it("empty string yields one empty line", () => {
    expect(Buffer.fromString("").lineCount()).toBe(1);
    expect(Buffer.fromString("").getLine(0)).toBe("");
  });

  it("toString joins lines with \\n", () => {
    expect(Buffer.fromString("a\nb\nc").toString()).toBe("a\nb\nc");
  });

  it("insertChar at cursor extends a line", () => {
    const b = Buffer.fromString("abc");
    b.insertChar(0, 1, "X");
    expect(b.getLine(0)).toBe("aXbc");
  });

  it("deleteChar at cursor shortens a line", () => {
    const b = Buffer.fromString("abc");
    b.deleteChar(0, 1);
    expect(b.getLine(0)).toBe("ac");
  });

  it("splitLine creates two lines from one", () => {
    const b = Buffer.fromString("hello world");
    b.splitLine(0, 5);
    expect(b.lineCount()).toBe(2);
    expect(b.getLine(0)).toBe("hello");
    expect(b.getLine(1)).toBe(" world");
  });

  it("joinLines collapses two lines into one", () => {
    const b = Buffer.fromString("hello\nworld");
    b.joinLines(0);
    expect(b.lineCount()).toBe(1);
    expect(b.getLine(0)).toBe("helloworld");
  });

  it("deleteLine removes a line; never goes below one line", () => {
    const b = Buffer.fromString("a\nb\nc");
    b.deleteLine(1);
    expect(b.lineCount()).toBe(2);
    expect(b.getLine(1)).toBe("c");

    const single = Buffer.fromString("only");
    single.deleteLine(0);
    expect(single.lineCount()).toBe(1);
    expect(single.getLine(0)).toBe("");
  });

  it("insertLine inserts a new line at index", () => {
    const b = Buffer.fromString("a\nc");
    b.insertLine(1, "b");
    expect(b.lineCount()).toBe(3);
    expect(b.getLine(1)).toBe("b");
  });
});
