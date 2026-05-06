/**
 * Per-region renderer. Implements SPEC §6.3:
 *   - Per-region buffer + version counter
 *   - update / append / scrollback / clear / notify
 *   - notify auto-clears after duration_ms; never auto-clears region content
 *
 * Renders directly into the region's DOM element. Spans are wrapped in
 * <span class="fg-X bg-Y bold"> per the cell content format (SPEC §4.4).
 *
 * Buffer model: each region holds an array of {@link Row} (the last N painted),
 * indexed by row number within the region. update replaces; append adds to the
 * end; scrollback prepends.
 */
import {
  type RegionAppendPayload,
  type RegionClearPayload,
  type RegionNotifyPayload,
  type RegionScrollbackPayload,
  type RegionUpdatePayload,
  type Row,
  type Span,
} from "./types.js";
import { type RegionDom } from "./layout.js";

interface RegionState {
  dom: RegionDom;
  buffer: Row[];
  version: number;
  notifyTimer?: number;
  deferred?: boolean;
}

export class RegionRenderer {

  private readonly states: Record<string, RegionState> = {};
  /** Set true between pointerdown and pointerup so repaints don't blow away
   * a drag-select in progress (a drag's selection is collapsed for most of
   * its lifetime, so a non-collapsed-selection check alone isn't enough). */
  private pointerDown = false;

  constructor(regions: Record<string, RegionDom>) {
    for (const [id, dom] of Object.entries(regions)) {
      this.states[id] = { dom, buffer: [], version: 0 };
    }
    const onDown = () => { this.pointerDown = true; };
    const onUp   = () => {
      this.pointerDown = false;
      // Defer the deferred-region flush to a macrotask so the browser has
      // finished applying the mouseup's selection state before we check
      // `selectionInside`. Without this the selection appears momentarily
      // collapsed inside our handler and the flush blasts the user's
      // drag-selection away.
      setTimeout(() => {
        for (const s of Object.values(this.states)) {
          if (s.deferred) { s.deferred = false; this.repaint(s); }
        }
      }, 0);
    };
    // Both pointer- and mouse-events to cover browsers/devices where one
    // chain doesn't fire reliably (some trackpad interactions).
    document.addEventListener("pointerdown", onDown);
    document.addEventListener("mousedown",   onDown);
    document.addEventListener("pointerup",   onUp);
    document.addEventListener("mouseup",     onUp);
  }

  /** Per-region versions, used by ws.ts to send region_versions on resume. */
  versions(): Record<string, number> {
    const out: Record<string, number> = {};
    for (const [id, s] of Object.entries(this.states)) out[id] = s.version;
    return out;
  }

  update(p: RegionUpdatePayload): void {
    const s = this.require(p.region);
    s.buffer = p.content.slice().sort((a, b) => a.row - b.row);
    s.version = p.version;
    s.dom.el.classList.remove("level-info", "level-warn", "level-alert");
    this.repaint(s);
  }

  append(p: RegionAppendPayload): void {
    const s = this.require(p.region);
    s.buffer = s.buffer.concat(p.content);
    s.version = p.version;
    this.repaint(s);
  }

  scrollback(p: RegionScrollbackPayload): void {
    const s = this.require(p.region);
    s.buffer = p.content.concat(s.buffer);
    this.repaint(s);
  }

  clear(p: RegionClearPayload): void {
    const s = this.require(p.region);
    s.buffer = [];
    s.dom.el.replaceChildren();
  }

  notify(p: RegionNotifyPayload): void {
    const s = this.require(p.region);
    s.dom.el.classList.remove("level-info", "level-warn", "level-alert");
    if (p.level) s.dom.el.classList.add(`level-${p.level}`);
    s.dom.el.replaceChildren(...rowsToNodes(p.content));
    if (s.notifyTimer) clearTimeout(s.notifyTimer);
    if (p.duration_ms && p.duration_ms > 0) {
      s.notifyTimer = window.setTimeout(() => {
        s.dom.el.replaceChildren();
        s.dom.el.classList.remove("level-info", "level-warn", "level-alert");
      }, p.duration_ms);
    }
  }

  private repaint(s: RegionState): void {
    // Defer repaints while the user is mid-drag (pointerdown→pointerup) or
    // has an active selection inside this region. Repainting during a drag
    // would blow away the DOM nodes the selection is anchored to and break
    // copy-paste from chat/NFOs/threads. We re-flush on pointerup or on the
    // next selectionchange that clears the selection.
    if (this.pointerDown) {
      s.deferred = true;
      return;
    }
    if (this.selectionInside(s.dom.el)) {
      const onChange = () => {
        if (!this.selectionInside(s.dom.el)) {
          document.removeEventListener("selectionchange", onChange);
          this.repaint(s);
        }
      };
      document.addEventListener("selectionchange", onChange);
      return;
    }
    s.dom.el.replaceChildren(...rowsToNodes(s.buffer));
    if (s.dom.id === "main") {
      // Auto-scroll the main region to the bottom on every paint, mirroring
      // the v0 artifact's append-and-scroll behaviour.
      s.dom.el.scrollTop = s.dom.el.scrollHeight;
    }
  }

  private selectionInside(el: HTMLElement): boolean {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return false;
    for (let i = 0; i < sel.rangeCount; i++) {
      const r = sel.getRangeAt(i);
      if (el.contains(r.commonAncestorContainer)) return true;
    }
    return false;
  }

  private require(name: string): RegionState {
    const s = this.states[name];
    if (!s) throw new Error(`unknown region: ${name}`);
    return s;
  }
}

function rowsToNodes(rows: Row[]): Node[] {
  // Group spans by row index into a series of <div class="line"> elements.
  // Rows with the same index merge their spans; the server may emit either
  // shape and the result is identical in the DOM.
  const sorted = rows.slice().sort((a, b) => a.row - b.row);
  return sorted.map(rowToNode);
}

function rowToNode(row: Row): HTMLDivElement {
  const div = document.createElement("div");
  div.className = "line";
  for (const span of row.spans) {
    div.appendChild(spanToNode(span));
  }
  return div;
}

function spanToNode(span: Span): HTMLSpanElement {
  const el = document.createElement("span");
  const classes: string[] = ["span"];
  if (span.fg && span.fg !== "default") classes.push(`fg-${normalise(span.fg)}`);
  if (span.bg && span.bg !== "default") classes.push(`bg-${normalise(span.bg)}`);
  if (span.bold) classes.push("bold");
  el.className = classes.join(" ");
  appendLinkified(el, span.text);
  return el;
}

/** Match http(s) URLs. Stops at whitespace, angle brackets, and a few
 *  punctuation chars that are commonly trailing punctuation rather than
 *  part of the URL. javascript:/data:/file: schemes are NOT matched, so
 *  no XSS surface beyond what the chat input already allows. */
const URL_RE = /(https?:\/\/[^\s<>()\[\]"']+[^\s<>()\[\]"'.,;:!?])/g;

/** PR-7: match `~slug` and `~handle/slug` cross-references. The
 *  lookbehind enforces a left-boundary so mid-word matches like
 *  `voidcore~slug` aren't picked up. The slug grammar mirrors the
 *  server-side {@code LinkGraphParser}. */
const SLUG_RE = /(?<=^|[\s(\[{,.;:])(~[a-z][a-z0-9-]*(?:\/[a-z][a-z0-9-]*)?)/g;

interface Match { idx: number; len: number; node: () => Node; }

function appendLinkified(el: HTMLElement, text: string): void {
  if (!text) { el.textContent = ""; return; }
  // Collect URL + slug matches and merge by idx so they emit in source order.
  const matches: Match[] = [];
  for (const m of text.matchAll(URL_RE)) {
    const idx = m.index ?? 0;
    matches.push({ idx, len: m[0].length, node: () => urlAnchor(m[0]) });
  }
  for (const m of text.matchAll(SLUG_RE)) {
    const idx = m.index ?? 0;
    const captured = m[1] ?? "";
    if (!captured) continue;
    matches.push({ idx, len: captured.length, node: () => slugSpan(captured) });
  }
  if (matches.length === 0) { el.textContent = text; return; }
  matches.sort((a, b) => a.idx - b.idx);
  let last = 0;
  for (const m of matches) {
    if (m.idx < last) continue; // overlap (shouldn't happen — patterns don't share chars)
    if (m.idx > last) {
      el.appendChild(document.createTextNode(text.slice(last, m.idx)));
    }
    el.appendChild(m.node());
    last = m.idx + m.len;
  }
  if (last < text.length) {
    el.appendChild(document.createTextNode(text.slice(last)));
  }
}

function urlAnchor(url: string): HTMLAnchorElement {
  const a = document.createElement("a");
  a.href = url;
  a.textContent = url;
  a.target = "_blank";
  a.rel = "noopener noreferrer";
  return a;
}

/** PR-7: render `~slug` as a styled span. Non-clickable in v1 — the
 *  visual affordance is the relationship indicator; intra-app
 *  navigation by slug is a separate UX ticket that needs a new
 *  client→server intent message type. */
function slugSpan(text: string): HTMLSpanElement {
  const s = document.createElement("span");
  s.className = "slug-link";
  s.textContent = text;
  return s;
}

function normalise(name: string): string {
  // SPEC §4.4 colour names: lowercase, underscores. The CSS uses dashes.
  return name.replace(/_/g, "-");
}
