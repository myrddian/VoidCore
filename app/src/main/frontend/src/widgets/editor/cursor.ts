import type { Buffer } from "./buffer.js";

export interface Cursor { line: number; col: number; }

export function clampCursor(buf: Buffer, c: Cursor): Cursor {
  const last = buf.lineCount() - 1;
  const line = Math.max(0, Math.min(last, c.line));
  const lineLen = buf.getLine(line).length;
  const col = Math.max(0, Math.min(lineLen, c.col));
  return { line, col };
}
