import { describe, it, expect } from "vitest";
import { transitionFromKey } from "../modes.js";

describe("transitionFromKey", () => {

  it("NORMAL + i → INSERT", () => {
    expect(transitionFromKey("NORMAL", "i")).toBe("INSERT");
  });

  it("NORMAL + : → COMMAND", () => {
    expect(transitionFromKey("NORMAL", ":")).toBe("COMMAND");
  });

  it("INSERT + Escape → NORMAL", () => {
    expect(transitionFromKey("INSERT", "Escape")).toBe("NORMAL");
  });

  it("COMMAND + Escape → NORMAL", () => {
    expect(transitionFromKey("COMMAND", "Escape")).toBe("NORMAL");
  });

  it("READ_ONLY ignores i / :", () => {
    expect(transitionFromKey("READ_ONLY", "i")).toBe("READ_ONLY");
    expect(transitionFromKey("READ_ONLY", ":")).toBe("READ_ONLY");
  });

  it("Unknown key in any mode is a no-op for transitions", () => {
    expect(transitionFromKey("NORMAL", "x")).toBe("NORMAL");
    expect(transitionFromKey("INSERT", "j")).toBe("INSERT");
  });
});
