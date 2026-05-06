/**
 * WebSocket client per SPEC §4.1, §4.5, §6.6.
 *
 *   - Subprotocol voidcore-node-v1
 *   - Auto-reconnect with exponential backoff: 1s → 2s → 4s, capped 30s
 *   - Status row shows RECONNECTING... during gaps; never auto-clears regions
 *   - On reconnect, sends auth.resume with stored token + last-seen
 *     region_versions (SPEC §4.5 sync detection)
 *
 * Resume reconciliation is driven by the server (sync=true keeps painted
 * state, sync=false brings frames). The client just forwards them through
 * the same dispatch path used for live messages.
 */
import { envelope } from "./envelope.js";
import { PROTOCOL_VERSION, type Envelope } from "./types.js";

export interface WsCallbacks {
  onMessage: (env: Envelope) => void;
  onStatus: (text: string) => void;
  /** Fired when the server closes the WS deliberately (code 1000). */
  onServerClose: () => void;
  getRegionVersions: () => Record<string, number>;
  getStoredToken: () => string | null;
  getIntent: () => string | undefined;
}

/**
 * Watchdog tolerance — if no frame arrives from the server for this long
 * we assume the connection has gone half-open (browser still reports
 * readyState=OPEN but no bytes are flowing). Force-close to trigger the
 * reconnect cycle. Server's HeartbeatScheduler sends a `system.heartbeat`
 * data frame every {@code voidcore.ws.heartbeat-seconds} (default 30s), so
 * 90s here = comfortable buffer for two missed heartbeats. Tightening
 * back down to ~25s waits on the diagnostic round investigating why
 * browser auto-pongs aren't reaching the server.
 */
const WATCHDOG_STALE_MS = 90_000;
/** How often the watchdog wakes up to check staleness. */
const WATCHDOG_TICK_MS = 10_000;

export class WsClient {

  private ws: WebSocket | null = null;
  private backoffMs = 0;
  private timer: number | null = null;
  private closedDeliberately = false;
  private firstConnect = true;
  /** Wall-clock time (ms) of the most recent inbound frame. 0 before first open. */
  private lastFrameAt = 0;
  /** Watchdog timer handle — checks {@link #lastFrameAt} periodically. */
  private watchdog: number | null = null;

  constructor(
    private readonly url: string,
    private readonly cb: WsCallbacks,
  ) {}

  start(): void {
    this.connect();
  }

  send(env: Envelope): void {
    const state = this.ws?.readyState ?? -1;
    // eslint-disable-next-line no-console
    console.debug("[ws] send", env.type, "readyState=" + readyStateName(state));
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(env));
    } else {
      // Drop on the floor with a console warning; in a queued model we'd
      // buffer here, but the BBS's request/response shape means anything
      // missed during a drop should be re-fetched after resume anyway.
      console.warn("ws not open, dropping outbound", env.type, "readyState=", readyStateName(state));
    }
  }

  close(): void {
    this.closedDeliberately = true;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    this.ws?.close();
  }

  private connect(): void {
    this.cb.onStatus(this.firstConnect ? "" : "RECONNECTING...");
    // eslint-disable-next-line no-console
    console.debug("[ws] connect", { url: this.url, firstConnect: this.firstConnect });
    const ws = new WebSocket(this.url, [PROTOCOL_VERSION]);
    this.ws = ws;

    ws.addEventListener("open", () => {
      // eslint-disable-next-line no-console
      console.debug("[ws] open");
      this.backoffMs = 0;
      this.lastFrameAt = Date.now();
      this.startWatchdog();
      this.cb.onStatus("");
      // Always send auth.resume on connect — when a token is present this
      // resumes the session, when absent (token=null) it serves only to
      // hand the URL fragment intent to the server so it survives the
      // login flow per SPEC §4.6. Server treats null token as "no resume,
      // just stash the intent".
      const token = this.cb.getStoredToken();
      const intent = this.cb.getIntent();
      if (token || intent) {
        ws.send(JSON.stringify(envelope("auth.resume", {
          token: token ?? null,
          intent: intent ?? null,
          region_versions: this.cb.getRegionVersions(),
        })));
      }
      this.firstConnect = false;
    });

    ws.addEventListener("message", (ev) => {
      // Reset the watchdog on EVERY frame (heartbeat, region.update,
      // input.prompt, etc.) — proves the inbound channel is alive.
      this.lastFrameAt = Date.now();
      try {
        const env = JSON.parse(ev.data as string) as Envelope;
        if (env.protocol_version !== PROTOCOL_VERSION) {
          console.error("protocol version mismatch:", env.protocol_version);
          return;
        }
        this.cb.onMessage(env);
      } catch (e) {
        console.error("bad message from server", e);
      }
    });

    ws.addEventListener("close", (ev) => {
      // eslint-disable-next-line no-console
      console.debug("[ws] close", { code: ev.code, reason: ev.reason, wasClean: ev.wasClean });
      this.stopWatchdog();
      this.ws = null;
      if (this.closedDeliberately) return;
      // Server-initiated NORMAL close (code 1000) means a deliberate
      // logout/goodbye; do not reconnect, do not auto-resume. Anything
      // else (1001 going away, 1006 abnormal, etc.) is a network drop
      // and we should reconnect with backoff.
      if (ev.code === 1000) {
        this.cb.onServerClose();
        return;
      }
      this.scheduleReconnect();
    });

    ws.addEventListener("error", (ev) => {
      // eslint-disable-next-line no-console
      console.debug("[ws] error", ev);
      // close fires after error; we handle reconnect there.
    });
  }

  private scheduleReconnect(): void {
    this.cb.onStatus("RECONNECTING...");
    this.backoffMs = nextBackoff(this.backoffMs);
    this.timer = window.setTimeout(() => this.connect(), this.backoffMs);
  }

  /**
   * Start the staleness watchdog. Wakes every {@link WATCHDOG_TICK_MS}
   * and force-closes the WS if no inbound frame has arrived in
   * {@link WATCHDOG_STALE_MS}. The close handler then schedules a
   * reconnect via the normal exponential-backoff path.
   *
   * <p>This catches the half-open WS case the browser's own
   * {@code readyState} won't surface — when the underlying TCP is dead
   * but no FIN/RST has been received, {@code readyState} stays at
   * {@code OPEN} and {@code ws.send} silently writes into the void.
   * The server's {@code system.heartbeat} data frame (every
   * {@code voidcore.ws.heartbeat-seconds}) is the liveness signal we wait
   * for; missing two of them in a row triggers the force-close.
   */
  private startWatchdog(): void {
    this.stopWatchdog();
    this.watchdog = window.setInterval(() => {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      const idle = Date.now() - this.lastFrameAt;
      if (idle > WATCHDOG_STALE_MS) {
        // eslint-disable-next-line no-console
        console.warn("[ws] watchdog: no server frame in " + idle + "ms — forcing close");
        // close() triggers the close listener → schedules a reconnect.
        try { this.ws.close(); } catch { /* ignore */ }
      }
    }, WATCHDOG_TICK_MS);
  }

  private stopWatchdog(): void {
    if (this.watchdog !== null) {
      clearInterval(this.watchdog);
      this.watchdog = null;
    }
  }
}

/** 0 → 1000 → 2000 → 4000 → ... cap 30000. */
function nextBackoff(prev: number): number {
  if (prev === 0) return 1_000;
  return Math.min(prev * 2, 30_000);
}

/** Human-readable WebSocket.readyState for diagnostic logs. */
function readyStateName(state: number): string {
  switch (state) {
    case 0: return "CONNECTING";
    case 1: return "OPEN";
    case 2: return "CLOSING";
    case 3: return "CLOSED";
    default: return "NULL";
  }
}

// ---------------------------------------------------------------------------
// Module-level singleton for outbound senders. Populated by the first
// WsClient.start() — the singleton pattern matches the single-WS architecture.
// ---------------------------------------------------------------------------

let _instance: WsClient | null = null;

/** Register the active WsClient so module-level senders can reach it. */
export function registerInstance(client: WsClient): void {
  _instance = client;
}

/**
 * Send a widget-originated message by wrapping it in the standard envelope.
 * Widgets call deps.sendMessage with a plain `{ type, ...fields }` object;
 * this helper adds the envelope fields required by the wire protocol.
 * Used as RenderDeps.sendMessage in main.ts.
 */
export function sendRaw(msg: unknown): void {
  if (!_instance) {
    console.warn("[ws] sendRaw: no active WsClient instance");
    return;
  }
  const raw = msg as Record<string, unknown>;
  const type = typeof raw["type"] === "string" ? raw["type"] : "unknown";
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { type: _type, ...payload } = raw;
  _instance.send(envelope(type, payload));
}

export function sendFieldCommit(widgetId: string, value: string): void {
  _instance?.send(envelope("field.commit", { widget_id: widgetId, value }));
}

export function sendFieldCancel(widgetId: string): void {
  _instance?.send(envelope("field.cancel", { widget_id: widgetId }));
}

export function sendEditorCommit(
  widgetId: string,
  content: string,
  action: "save" | "save_quit",
): void {
  _instance?.send(
    envelope("editor.commit", { widget_id: widgetId, content, action }),
  );
}

export function sendEditorCancel(widgetId: string, force: boolean): void {
  _instance?.send(envelope("editor.cancel", { widget_id: widgetId, force }));
}

export function sendEditorSnapshot(widgetId: string, content: string): void {
  _instance?.send(
    envelope("editor.snapshot", { widget_id: widgetId, content }),
  );
}

export function sendFocusMove(from: string, direction: "next" | "prev"): void {
  _instance?.send(envelope("focus.move", { from, direction }));
}
