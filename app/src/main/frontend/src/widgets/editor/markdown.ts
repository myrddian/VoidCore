export type Token =
  | { kind: "plain";   text: string }
  | { kind: "heading"; text: string }
  | { kind: "bold";    text: string }
  | { kind: "italic";  text: string }
  | { kind: "code";    text: string }
  | { kind: "fence";   text: string }
  | { kind: "link";    text: string }
  | { kind: "url";     text: string }
  | { kind: "slug";    text: string }
  | { kind: "bullet";  text: string };

export interface TokeniseResult {
  tokens: Token[];
  fenceState: boolean;
}

const FENCE = /^```/;
const HEADING = /^#{1,6}\s/;
const BULLET = /^(\s*[-*+]\s)/;
const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*]+\*)|(\[[^\]]+\]\([^)]+\))|(~[a-z0-9-]+)/;

export function tokeniseMarkdown(line: string, fenceState: boolean): TokeniseResult {
  if (FENCE.test(line)) {
    return { tokens: [{ kind: "fence", text: line }], fenceState: !fenceState };
  }
  if (fenceState) {
    return { tokens: [{ kind: "fence", text: line }], fenceState: true };
  }
  if (HEADING.test(line)) {
    return { tokens: [{ kind: "heading", text: line }], fenceState };
  }
  const bullet = line.match(BULLET);
  const tokens: Token[] = [];
  let rest = line;
  if (bullet) {
    tokens.push({ kind: "bullet", text: bullet[1]! });
    rest = line.slice(bullet[1]!.length);
  }
  scanInline(rest, tokens);
  return { tokens, fenceState };
}

function scanInline(s: string, out: Token[]): void {
  let cursor = 0;
  while (cursor < s.length) {
    const tail = s.slice(cursor);
    const m = tail.match(INLINE);
    if (!m || m.index === undefined) {
      out.push({ kind: "plain", text: tail });
      return;
    }
    if (m.index > 0) {
      out.push({ kind: "plain", text: tail.slice(0, m.index) });
    }
    const match = m[0];
    if (match.startsWith("`")) {
      out.push({ kind: "code", text: match });
    } else if (match.startsWith("**")) {
      out.push({ kind: "bold", text: match });
    } else if (match.startsWith("*")) {
      out.push({ kind: "italic", text: match });
    } else if (match.startsWith("[")) {
      const split = match.indexOf("](");
      out.push({ kind: "link", text: match.slice(0, split + 1) });
      out.push({ kind: "url",  text: match.slice(split + 1) });
    } else if (match.startsWith("~")) {
      out.push({ kind: "slug", text: match });
    }
    cursor += m.index + match.length;
  }
}
