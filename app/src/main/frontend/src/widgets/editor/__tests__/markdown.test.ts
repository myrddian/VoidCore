import { describe, it, expect } from "vitest";
import { tokeniseMarkdown, type Token } from "../markdown.js";

describe("tokeniseMarkdown", () => {

  it("heading line — single token of kind 'heading'", () => {
    const tokens = tokeniseMarkdown("# Hello", false);
    expect(tokens.tokens.map((t: Token) => t.kind)).toEqual(["heading"]);
    expect(tokens.fenceState).toBe(false);
  });

  it("plain line — one 'plain' token", () => {
    const t = tokeniseMarkdown("hello world", false);
    expect(t.tokens.map(x => x.kind)).toEqual(["plain"]);
  });

  it("inline code is its own token", () => {
    const t = tokeniseMarkdown("see `foo()` here", false);
    expect(t.tokens.map(x => x.kind)).toEqual(["plain", "code", "plain"]);
  });

  it("bold + italic split out", () => {
    const t = tokeniseMarkdown("a **bold** and *italic* one", false);
    expect(t.tokens.map(x => x.kind))
      .toEqual(["plain", "bold", "plain", "italic", "plain"]);
  });

  it("link splits into link + url", () => {
    const t = tokeniseMarkdown("see [docs](https://x.y)", false);
    expect(t.tokens.map(x => x.kind))
      .toEqual(["plain", "link", "url"]);
  });

  it("~slug renders as slug-ref", () => {
    const t = tokeniseMarkdown("see ~scene-overview here", false);
    expect(t.tokens.map(x => x.kind)).toEqual(["plain", "slug", "plain"]);
  });

  it("list bullet line tags the bullet", () => {
    const t = tokeniseMarkdown("- bullet item", false);
    expect(t.tokens[0]?.kind).toBe("bullet");
  });

  it("fenced block opens + flips state", () => {
    const t = tokeniseMarkdown("```", false);
    expect(t.tokens.map(x => x.kind)).toEqual(["fence"]);
    expect(t.fenceState).toBe(true);
  });

  it("inside fenced block, all content is fence-styled", () => {
    const t = tokeniseMarkdown("anything here", true);
    expect(t.tokens.map(x => x.kind)).toEqual(["fence"]);
    expect(t.fenceState).toBe(true);
  });

  it("closing fence flips state back", () => {
    const t = tokeniseMarkdown("```", true);
    expect(t.fenceState).toBe(false);
  });
});
