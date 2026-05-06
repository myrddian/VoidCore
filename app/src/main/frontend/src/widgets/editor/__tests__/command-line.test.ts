import { describe, it, expect } from "vitest";
import { parseCommand, type Command } from "../command-line.js";

describe("parseCommand", () => {
  const cases: [string, Command][] = [
    [":w",       { kind: "save" }],
    [":q",       { kind: "quit" }],
    [":wq",      { kind: "save_quit" }],
    [":q!",      { kind: "quit", force: true }],
    [":e!",      { kind: "reload" }],
    [":set ro",  { kind: "toggle_ro" }],
  ];
  for (const [input, expected] of cases) {
    it(`parses ${JSON.stringify(input)} → ${JSON.stringify(expected)}`, () => {
      expect(parseCommand(input)).toEqual(expected);
    });
  }

  it("ZZ (no leading colon) parses as save_quit", () => {
    expect(parseCommand("ZZ")).toEqual({ kind: "save_quit" });
  });

  it("unknown command returns 'unknown'", () => {
    expect(parseCommand(":foo")).toEqual({ kind: "unknown", input: ":foo" });
  });

  it("blank string returns 'unknown'", () => {
    expect(parseCommand("")).toEqual({ kind: "unknown", input: "" });
  });
});
