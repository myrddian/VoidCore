import { type Buffer } from "./buffer.js";
import { clampCursor, type Cursor } from "./cursor.js";

const VIEWPORT_LINES = 20;

/**
 * Apply a motion key to a cursor, returning the new cursor. Pure
 * function; doesn't mutate the buffer. Unknown keys return cursor unchanged.
 */
export function applyMotion(buf: Buffer, c: Cursor, key: string): Cursor {
  switch (key) {
    case "h": case "ArrowLeft":
      return clampCursor(buf, { line: c.line, col: c.col - 1 });
    case "l": case "ArrowRight":
      return clampCursor(buf, { line: c.line, col: c.col + 1 });
    case "j": case "ArrowDown":
      return clampCursor(buf, { line: c.line + 1, col: c.col });
    case "k": case "ArrowUp":
      return clampCursor(buf, { line: c.line - 1, col: c.col });
    case "0": case "Home":
      return { line: c.line, col: 0 };
    case "$": case "End":
      return { line: c.line, col: buf.getLine(c.line).length };
    case "^": {
      const line = buf.getLine(c.line);
      const m = line.match(/^\s*/);
      return { line: c.line, col: (m?.[0].length ?? 0) };
    }
    case "gg":
      return { line: 0, col: 0 };
    case "G":
      return { line: buf.lineCount() - 1, col: 0 };
    case "w":
      return wordForward(buf, c);
    case "b":
      return wordBackward(buf, c);
    case "PageDown":
      return clampCursor(buf, { line: c.line + VIEWPORT_LINES, col: c.col });
    case "PageUp":
      return clampCursor(buf, { line: c.line - VIEWPORT_LINES, col: c.col });
    case "Ctrl-d":
      return clampCursor(buf, { line: c.line + Math.floor(VIEWPORT_LINES / 2), col: c.col });
    case "Ctrl-u":
      return clampCursor(buf, { line: c.line - Math.floor(VIEWPORT_LINES / 2), col: c.col });
    default:
      return c;
  }
}

function wordForward(buf: Buffer, c: Cursor): Cursor {
  const line = buf.getLine(c.line);
  let i = c.col;
  while (i < line.length && /\S/.test(line[i] ?? "")) i++;
  while (i < line.length && /\s/.test(line[i] ?? "")) i++;
  if (i < line.length) return { line: c.line, col: i };
  if (c.line + 1 < buf.lineCount()) return { line: c.line + 1, col: 0 };
  return c;
}

function wordBackward(buf: Buffer, c: Cursor): Cursor {
  const line = buf.getLine(c.line);
  let i = c.col - 1;
  while (i >= 0 && /\s/.test(line[i] ?? "")) i--;
  while (i >= 0 && /\S/.test(line[i] ?? "")) i--;
  return { line: c.line, col: Math.max(0, i + 1) };
}
