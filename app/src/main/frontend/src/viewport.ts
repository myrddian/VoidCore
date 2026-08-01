/**
 * Viewport reporting — `viewport.resize` per SPEC §4.3 / §6.7.
 *
 * The server renders in character cells, so it needs the canvas size in
 * cells, not pixels. We measure one cell from a probe rendered in the
 * region's own font, divide, and report. The server holds the result on
 * the session and lets the active screen reflow.
 *
 * Bounds mirror the server's validation on `ClientMessage.ViewportResize`
 * (cols 20..500, rows 10..500). Clamping here rather than sending an
 * out-of-range value keeps a very small or very large window from
 * tripping a protocol error.
 */

export interface ViewportSize {
  cols: number;
  rows: number;
}

const MIN_COLS = 20;
const MAX_COLS = 500;
const MIN_ROWS = 10;
const MAX_ROWS = 500;

/** Characters in the probe string. Wide enough that sub-pixel advance
 *  widths average out instead of compounding. */
const PROBE_CHARS = 50;

const DEFAULT_DEBOUNCE_MS = 150;

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}

/**
 * Cells that fit in a box, given a cell's size. Pure — the arithmetic is
 * here so it can be tested without a layout engine.
 *
 * Returns null for non-finite or non-positive inputs: a hidden or
 * not-yet-laid-out element measures zero, and reporting a bogus size is
 * worse than reporting none.
 */
export function viewportFromMetrics(
  boxWidth: number, boxHeight: number,
  cellWidth: number, cellHeight: number,
): ViewportSize | null {
  const finite = [boxWidth, boxHeight, cellWidth, cellHeight].every(
    (n) => Number.isFinite(n) && n > 0);
  if (!finite) return null;
  return {
    cols: clamp(Math.floor(boxWidth / cellWidth), MIN_COLS, MAX_COLS),
    rows: clamp(Math.floor(boxHeight / cellHeight), MIN_ROWS, MAX_ROWS),
  };
}

/**
 * Measure one character cell inside `el`, in the element's own font.
 * The probe is inserted, measured, and removed synchronously, so it never
 * paints.
 */
export function measureCell(el: HTMLElement): { width: number; height: number } {
  const probe = document.createElement("span");
  probe.textContent = "0".repeat(PROBE_CHARS);
  probe.style.position = "absolute";
  probe.style.visibility = "hidden";
  probe.style.whiteSpace = "pre";
  el.appendChild(probe);
  const rect = probe.getBoundingClientRect();
  probe.remove();
  return { width: rect.width / PROBE_CHARS, height: rect.height };
}

/** Measure `el`'s usable size in character cells. */
export function measureViewport(el: HTMLElement): ViewportSize | null {
  const cell = measureCell(el);
  const box = el.getBoundingClientRect();
  return viewportFromMetrics(box.width, box.height, cell.width, cell.height);
}

/**
 * Watches for size changes and reports them, debounced and de-duplicated.
 *
 * De-duplication matters more than it looks: a drag-resize fires a torrent
 * of pixel-level events, but the cell count only changes occasionally, and
 * every report costs a server-side repaint.
 */
export class ViewportReporter {
  private last: ViewportSize | null = null;
  private timer: number | null = null;

  constructor(
    private readonly measure: () => ViewportSize | null,
    private readonly send: (size: ViewportSize) => void,
    private readonly debounceMs: number = DEFAULT_DEBOUNCE_MS,
  ) {}

  start(): void {
    window.addEventListener("resize", this.onResize);
    // Report once up front so the server isn't stuck on the 80x24 default
    // for a client that never resizes.
    this.reportNow();
  }

  stop(): void {
    window.removeEventListener("resize", this.onResize);
    if (this.timer != null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /** Force the next report through even if the size is unchanged — used
   *  on reconnect, where the server may be a fresh session at 80x24. */
  resetBaseline(): void {
    this.last = null;
  }

  reportNow(): void {
    const size = this.measure();
    if (!size) return;
    if (this.last && this.last.cols === size.cols && this.last.rows === size.rows) return;
    this.last = size;
    this.send(size);
  }

  private onResize = (): void => {
    if (this.timer != null) clearTimeout(this.timer);
    this.timer = window.setTimeout(() => {
      this.timer = null;
      this.reportNow();
    }, this.debounceMs);
  };
}
