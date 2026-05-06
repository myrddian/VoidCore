import { type Buffer } from "./buffer.js";
import { type Cursor, clampCursor } from "./cursor.js";
import { type Mode } from "./modes.js";

export interface EditResult {
  cursor: Cursor;
  modeOverride?: Mode;
}

/**
 * Apply a NORMAL-mode edit operator. Mutates the buffer; returns the
 * post-edit cursor + optional mode override (insert-entry operators).
 */
export function applyEdit(buf: Buffer, c: Cursor, key: string): EditResult {
  switch (key) {
    case "x": {
      buf.deleteChar(c.line, c.col);
      const len = buf.getLine(c.line).length;
      return { cursor: { line: c.line, col: Math.min(c.col, Math.max(0, len - 1)) } };
    }
    case "X": {
      if (c.col > 0) {
        buf.deleteChar(c.line, c.col - 1);
        return { cursor: { line: c.line, col: c.col - 1 } };
      }
      return { cursor: c };
    }
    case "dd":
      buf.deleteLine(c.line);
      return { cursor: clampCursor(buf, { line: c.line, col: 0 }) };
    case "D":
      buf.setLine(c.line, buf.getLine(c.line).slice(0, c.col));
      return { cursor: c };
    case "J": {
      if (c.line + 1 < buf.lineCount()) {
        const cur = buf.getLine(c.line).replace(/\s+$/, "");
        const next = buf.getLine(c.line + 1).replace(/^\s+/, "");
        buf.setLine(c.line, cur + " " + next);
        buf.deleteLine(c.line + 1);
      }
      return { cursor: c };
    }
    case "o":
      buf.insertLine(c.line + 1, "");
      return { cursor: { line: c.line + 1, col: 0 }, modeOverride: "INSERT" };
    case "O":
      buf.insertLine(c.line, "");
      return { cursor: { line: c.line, col: 0 }, modeOverride: "INSERT" };
    case "i": return { cursor: c, modeOverride: "INSERT" };
    case "a":
      return { cursor: { line: c.line, col: Math.min(c.col + 1, buf.getLine(c.line).length) },
               modeOverride: "INSERT" };
    case "I":
      return { cursor: { line: c.line, col: 0 }, modeOverride: "INSERT" };
    case "A":
      return { cursor: { line: c.line, col: buf.getLine(c.line).length },
               modeOverride: "INSERT" };
    default:
      return { cursor: c };
  }
}
