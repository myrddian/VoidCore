/**
 * How many buffer lines the editor shows.
 *
 * Was a hardcoded 20, duplicated across editor.ts, motions.ts and
 * render.ts. Now derived from the client viewport the same measurement
 * `viewport.resize` reports, so a tall window shows more of the document
 * and a phone with the keyboard open shows less — instead of both
 * pretending to be 20 lines tall.
 */

/** Used when the viewport hasn't been measured yet — the historical value. */
export const DEFAULT_VIEWPORT_LINES = 20;

/**
 * Rows the editor gives up to surrounding chrome: the screen's header, a
 * rule, the key menu. Approximate on purpose — being one row conservative
 * costs a line of text, being one row optimistic clips the last line.
 */
const CHROME_LINES = 4;

const MIN_VIEWPORT_LINES = 5;
const MAX_VIEWPORT_LINES = 200;

/**
 * Editor height in buffer lines, given the reported viewport height in
 * character cells. Null (nothing reported yet) yields the default.
 */
export function editorViewportLines(reportedRows: number | null): number {
  if (reportedRows == null || !Number.isFinite(reportedRows) || reportedRows <= 0) {
    return DEFAULT_VIEWPORT_LINES;
  }
  const usable = Math.floor(reportedRows) - CHROME_LINES;
  return Math.max(MIN_VIEWPORT_LINES, Math.min(usable, MAX_VIEWPORT_LINES));
}
