import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ViewportReporter, viewportFromMetrics, type ViewportSize } from "../viewport.js";

describe("viewportFromMetrics", () => {
  it("reports whole cells that fit", () => {
    expect(viewportFromMetrics(800, 480, 10, 20)).toEqual({ cols: 80, rows: 24 });
  });

  it("floors partial cells rather than rounding up", () => {
    // 806/10 = 80.6 — the 81st column would be clipped.
    expect(viewportFromMetrics(806, 495, 10, 20)).toEqual({ cols: 80, rows: 24 });
  });

  it("clamps to the server's validated range", () => {
    // ClientMessage.ViewportResize is @Min(20)@Max(500) / @Min(10)@Max(500).
    // Sending outside that is a protocol error, so clamp instead.
    expect(viewportFromMetrics(50, 40, 10, 20)).toEqual({ cols: 20, rows: 10 });
    expect(viewportFromMetrics(99_999, 99_999, 10, 20)).toEqual({ cols: 500, rows: 500 });
  });

  it("reports nothing when the element has not been laid out", () => {
    // A hidden or detached element measures zero. Reporting a bogus size
    // is worse than reporting none.
    expect(viewportFromMetrics(0, 0, 10, 20)).toBeNull();
    expect(viewportFromMetrics(800, 480, 0, 0)).toBeNull();
    expect(viewportFromMetrics(NaN, 480, 10, 20)).toBeNull();
  });
});

describe("ViewportReporter", () => {
  let sent: ViewportSize[];
  let size: ViewportSize | null;

  beforeEach(() => {
    vi.useFakeTimers();
    sent = [];
    size = { cols: 80, rows: 24 };
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function reporter(debounceMs = 150): ViewportReporter {
    return new ViewportReporter(() => size, (s) => { sent.push(s); }, debounceMs);
  }

  it("reports once on start so the server is not stuck on the default", () => {
    const r = reporter();
    r.start();

    expect(sent).toEqual([{ cols: 80, rows: 24 }]);
    r.stop();
  });

  it("does not report a size that has not changed", () => {
    const r = reporter();
    r.start();
    sent.length = 0;

    window.dispatchEvent(new Event("resize"));
    vi.advanceTimersByTime(200);

    // Pixels changed; cells did not. Every report costs a server repaint.
    expect(sent).toEqual([]);
    r.stop();
  });

  it("coalesces a burst of resizes into one report", () => {
    const r = reporter();
    r.start();
    sent.length = 0;

    size = { cols: 64, rows: 20 };
    for (let i = 0; i < 20; i++) window.dispatchEvent(new Event("resize"));
    vi.advanceTimersByTime(200);

    expect(sent).toEqual([{ cols: 64, rows: 20 }]);
    r.stop();
  });

  it("stops reporting once stopped", () => {
    const r = reporter();
    r.start();
    sent.length = 0;
    r.stop();

    size = { cols: 100, rows: 40 };
    window.dispatchEvent(new Event("resize"));
    vi.advanceTimersByTime(200);

    expect(sent).toEqual([]);
  });

  it("re-reports an unchanged size after the baseline is reset", () => {
    // Reconnect: the server may be a fresh session back at 80x24, so the
    // client's "already sent that" memory has to be cleared.
    const r = reporter();
    r.start();
    sent.length = 0;

    r.resetBaseline();
    r.reportNow();

    expect(sent).toEqual([{ cols: 80, rows: 24 }]);
    r.stop();
  });
});
