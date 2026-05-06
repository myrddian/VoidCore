import { randomUUID } from "node:crypto";

export const PROTOCOL_VERSION = "voidcore-door-v1";

export function connectDoor(options) {
  return new DoorClient(options);
}

export function createEnvelope({
  id = null,
  type,
  payload = {},
  protocolVersion = PROTOCOL_VERSION,
  seq = 0,
  mac = null
} = {}) {
  return {
    id,
    type,
    protocol_version: protocolVersion,
    seq,
    mac,
    payload
  };
}

export function createScreenStack() {
  return new ScreenStack();
}

export function createDialogueScreen({
  id,
  promptLabel = "talk:",
  historyLimit = 40,
  exitCommands = ["leave", "walk", "back", "bye", "exit"],
  initialLead = null,
  initialNote = "The line opens.",
  blankInputNote = "Say something, or hit Esc to step away.",
  playerEntry = defaultPlayerEntry,
  thinking = null,
  open,
  reply,
  render,
  onScene = null,
  onExit = null
}) {
  if (typeof open !== "function") {
    throw new Error("createDialogueScreen requires open(session)");
  }
  if (typeof reply !== "function") {
    throw new Error("createDialogueScreen requires reply(session, input)");
  }
  if (typeof render !== "function") {
    throw new Error("createDialogueScreen requires render(session, view)");
  }

  const transcript = [];
  let lead = initialLead;
  let note = initialNote;
  let stopAnimation = null;

  const resolvedPromptLabel = (session) =>
    typeof promptLabel === "function" ? promptLabel(session) : promptLabel;

  const paint = (session, overrides = {}) => {
    session.paint(render(session, {
      transcript,
      lead,
      note: overrides.note ?? note,
      thinking: overrides.thinking ?? null
    }));
    session.prompt({ label: resolvedPromptLabel(session), maxLength: 160 });
  };

  const appendEntries = (entries = []) => {
    for (const entry of entries) {
      if (!entry?.text) continue;
      transcript.push(entry);
    }
    if (transcript.length > historyLimit) {
      transcript.splice(0, transcript.length - historyLimit);
    }
  };

  const closeAnimation = () => {
    stopAnimation?.();
    stopAnimation = null;
  };

  const startThinking = (session, context) => {
    if (!thinking) return null;
    const base = typeof thinking === "function" ? thinking(session, context) : String(thinking);
    return startFrameAnimation({
      onFrame(frame) {
        paint(session, { thinking: `${base} ${frame}` });
      }
    });
  };

  const applyScene = async (session, scene, context) => {
    appendEntries(scene?.entries ?? []);
    if (scene && Object.prototype.hasOwnProperty.call(scene, "lead")) lead = scene.lead;
    if (scene?.note) note = scene.note;
    if (typeof onScene === "function") {
      await onScene(session, scene, { transcript, lead, note }, context);
    }
    paint(session);
  };

  const runScene = async (session, producer, context) => {
    stopAnimation = startThinking(session, context);
    try {
      const scene = await producer();
      closeAnimation();
      await applyScene(session, scene, context);
      return scene;
    } catch (error) {
      closeAnimation();
      throw error;
    }
  };

  return {
    id,
    async onEnter(session) {
      await runScene(session, () => open(session), { isInitial: true, input: null });
    },
    async onExit(session, stack, next, reason) {
      closeAnimation();
      if (typeof onExit === "function") {
        await onExit(session, { transcript, lead, note }, { stack, next, reason });
      }
    },
    async onLine(session, text, stack) {
      const input = String(text ?? "").trim();
      if (!input) {
        paint(session, { note: blankInputNote });
        return true;
      }
      if (exitCommands.map(normalizeChoice).includes(normalizeChoice(input))) {
        await stack.pop(session, "walk-away");
        return true;
      }
      appendEntries([playerEntry(session, input)]);
      paint(session, { thinking: typeof thinking === "function" ? thinking(session, { isInitial: false, input }) : "oracle -" });
      await runScene(session, () => reply(session, input), { isInitial: false, input });
      return true;
    },
    async onKey(session, key, _modifiers, stack) {
      if (isEscapeKey(key)) {
        await stack.pop(session, "walk-away");
        return true;
      }
      return false;
    }
  };
}

export function createOptionScreen({
  id,
  promptLabel = "select:",
  promptMode = null,
  options,
  render,
  initialNote = null,
  onUnknown = null,
  onEnter = null,
  onEscape = null,
  loadOptions = null,
  loading = null
}) {
  if (!Array.isArray(options) || options.length === 0) {
    throw new Error("createOptionScreen requires a non-empty options array");
  }
  if (typeof render !== "function") {
    throw new Error("createOptionScreen requires render(session, view)");
  }

  let note = initialNote;

  const resolvedPromptLabel = (session) =>
    typeof promptLabel === "function" ? promptLabel(session) : promptLabel;

  const paint = (session, override = null) => {
    session.paint(render(session, { options, note: override ?? note }));
    const mode = resolvedPromptMode();
    if (mode === "key") {
      session.prompt({ mode: "keystroke", label: resolvedPromptLabel(session), validKeys: validKeys().join("") });
    } else {
      session.prompt({ label: resolvedPromptLabel(session), maxLength: 80 });
    }
  };

  const setOptions = (nextOptions) => {
    options.splice(0, options.length, ...(Array.isArray(nextOptions) ? nextOptions : []));
  };

  const setNote = (nextNote) => {
    note = nextNote;
  };

  const resolvedPromptMode = () => {
    if (promptMode) return promptMode;
    const keys = options.map((option) => String(option.key ?? "").trim());
    if (keys.length > 0 && keys.every((key) => key.length === 1)) {
      return "key";
    }
    return "line";
  };

  const validKeys = () =>
    options
      .map((option) => String(option.key ?? "").trim().toUpperCase())
      .filter(Boolean);

  const matchOption = (value) => {
    const target = normalizeChoice(value);
    return options.find((option) => {
      const aliases = [
        option.key,
        ...(option.aliases ?? []),
        option.label
      ].map(normalizeChoice);
      return aliases.includes(target);
    }) ?? null;
  };

  return {
    id,
    async onEnter(session, stack, previous, reason) {
      const ctx = { stack, previous, reason, options, note, paint, setOptions, setNote };
      let stopAnimation = null;
      if (typeof loadOptions === "function") {
        if (loading) {
          stopAnimation = startFrameAnimation({
            frames: loading.frames ?? ["-", "\\", "|", "/"],
            intervalMs: loading.intervalMs ?? 180,
            onFrame(frame) {
              const message = typeof loading === "function"
                ? loading(session, { ...ctx, frame })
                : typeof loading.message === "function"
                  ? loading.message(session, { ...ctx, frame })
                  : `${loading.message ?? "loading"} ${frame}`;
              paint(session, message);
            }
          });
        }
        try {
          const loadedOptions = await loadOptions(session, ctx);
          if (Array.isArray(loadedOptions)) {
            setOptions(loadedOptions);
          }
        } finally {
          stopAnimation?.();
        }
      }
      if (typeof onEnter === "function") {
        await onEnter(session, { stack, previous, reason, options, note, paint, setOptions, setNote });
      }
      paint(session);
    },
    async onLine(session, text, stack) {
      const input = String(text ?? "").trim();
      const option = matchOption(input);
      if (!option) {
        if (typeof onUnknown === "function") {
          await onUnknown(session, input, { stack, options, paint });
        } else {
          paint(session, `Unknown option: ${input}`);
        }
        return true;
      }
      await option.action?.(session, { stack, option, options, paint });
      return true;
    },
    async onKey(session, key, _modifiers, stack) {
      if (isEscapeKey(key)) {
        if (typeof onEscape === "function") {
          await onEscape(session, { stack, options, paint });
          return true;
        }
        // Default: Esc pops back to the previous screen so prompt labels
        // like "[esc] route:" do what they advertise without each caller
        // having to wire onEscape.
        await stack.pop(session, "escape");
        return true;
      }
      const option = matchOption(key);
      if (!option) return false;
      await option.action?.(session, { stack, option, options, paint });
      return true;
    }
  };
}

export function startFrameAnimation({
  frames = ["-", "\\", "|", "/"],
  intervalMs = 180,
  onFrame
} = {}) {
  if (typeof onFrame !== "function") {
    throw new Error("startFrameAnimation requires onFrame");
  }
  let index = 0;
  onFrame(frames[index % frames.length], index);
  index += 1;
  const timer = setInterval(() => {
    onFrame(frames[index % frames.length], index);
    index += 1;
  }, intervalMs);
  return () => clearInterval(timer);
}

export function rowsFromLines(lines, { fg = "default", bg = null, bold = null, startRow = 0 } = {}) {
  return lines.map((text, index) => ({
    row: startRow + index,
    spans: [{ text, fg, bg, bold }]
  }));
}

export class ScreenStack {
  constructor() {
    this.stacks = new WeakMap();
  }

  current(session) {
    const stack = this.stacks.get(session) ?? [];
    return stack.at(-1) ?? null;
  }

  async setRoot(session, screen) {
    this.stacks.set(session, [screen]);
    await screen?.onEnter?.(session, this, null, "root");
    return screen;
  }

  async push(session, screen, reason = "push") {
    const stack = this.ensure(session);
    const previous = stack.at(-1) ?? null;
    stack.push(screen);
    await screen?.onEnter?.(session, this, previous, reason);
    return screen;
  }

  async pop(session, reason = "pop") {
    const stack = this.ensure(session);
    if (stack.length <= 1) return false;
    const exiting = stack.pop();
    const next = stack.at(-1) ?? null;
    await exiting?.onExit?.(session, this, next, reason);
    await next?.onResume?.(session, this, exiting, reason);
    return true;
  }

  async replace(session, screen, reason = "replace") {
    const stack = this.ensure(session);
    const previous = stack.pop() ?? null;
    await previous?.onExit?.(session, this, screen, reason);
    stack.push(screen);
    await screen?.onEnter?.(session, this, previous, reason);
    return screen;
  }

  async dispatchLine(session, text, payload = null, client = null) {
    const screen = this.current(session);
    if (!screen?.onLine) return false;
    const handled = await screen.onLine(session, text, this, payload, client);
    return handled !== false;
  }

  async dispatchKey(session, key, modifiers = [], payload = null, client = null) {
    const screen = this.current(session);
    if (!screen?.onKey) return false;
    const handled = await screen.onKey(session, key, modifiers, this, payload, client);
    return handled !== false;
  }

  ensure(session) {
    if (!this.stacks.has(session)) {
      this.stacks.set(session, []);
    }
    return this.stacks.get(session);
  }
}

export class DoorClient {
  constructor({
    url = process.env.BBS_DOOR_URL ?? "ws://localhost:8080/ws/door",
    manifest,
    onWelcome = null,
    onEnvelope = null,
    onAttach = null,
    onLine = null,
    onKey = null,
    onTime = null,
    onDetach = null,
    onShutdown = null,
    logger = console,
    reconnectDelayMs = 3000
  }) {
    if (!manifest?.door_id) {
      throw new Error("manifest.door_id is required");
    }
    this.url = url;
    this.manifest = manifest;
    this.onWelcome = onWelcome;
    this.onEnvelope = onEnvelope;
    this.onAttach = onAttach;
    this.onLine = onLine;
    this.onKey = onKey;
    this.onTime = onTime;
    this.onDetach = onDetach;
    this.onShutdown = onShutdown;
    this.logger = logger;
    this.reconnectDelayMs = reconnectDelayMs;
    this.sessions = new Map();
    this.pending = new Map();
    this.doorSessionToken = null;
    this.ws = null;
    this.reconnectTimer = null;
    this.closed = false;
    this.connect();
  }

  send(type, payload, id = null) {
    return this.sendEnvelope(createEnvelope({ id, type, payload }));
  }

  sendEnvelope(envelope) {
    if (!this.ws || !isWebSocketOpen(this.ws)) {
      this.logger?.warn?.(`door send dropped while disconnected: type=${envelope?.type ?? "?"}`);
      return false;
    }
    try {
      this.ws.send(JSON.stringify(envelope));
      return true;
    } catch (error) {
      this.logger?.error?.("door send failed", error);
      return false;
    }
  }

  close() {
    this.closed = true;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.ws?.close();
  }

  async handleMessage(raw) {
    const env = JSON.parse(await readMessageData(raw));
    const payload = env.payload ?? {};

    if (env.id && this.pending.has(env.id)) {
      if (this.resolvePending(env)) return;
    }

    if (this.onEnvelope) {
      const handled = await this.onEnvelope(env, this);
      if (handled === true) return;
    }

    switch (env.type) {
      case "welcome":
        this.doorSessionToken = payload.door_session_token ?? null;
        if (this.onWelcome) await this.onWelcome(payload, this);
        break;
      case "manifest_rejected":
        throw new Error(payload.reason ?? "door manifest rejected");
      case "attach":
        await this.handleAttach(payload);
        break;
      case "input.line":
        await this.onLine?.(this.sessions.get(payload.session_id), payload.text ?? "", payload, this);
        break;
      case "input.key":
        await this.onKey?.(
          this.sessions.get(payload.session_id),
          payload.key ?? "",
          payload.modifiers ?? [],
          payload,
          this
        );
        break;
      case "time.tick":
        await this.handleTimeTick(payload);
        break;
      case "detach":
        await this.handleDetach(payload);
        break;
      case "shutdown":
        await this.handleShutdown(payload);
        break;
      default:
        break;
    }
  }

  resolvePending(env) {
    const pending = this.pending.get(env.id);
    if (!pending) return false;
    const payload = env.payload ?? {};
    switch (env.type) {
      case "storage.value":
        this.pending.delete(env.id);
        pending.resolve({
          found: true,
          scope: payload.scope,
          key: payload.key,
          value: payload.value,
          version: payload.version
        });
        return true;
      case "storage.miss":
        this.pending.delete(env.id);
        pending.resolve(null);
        return true;
      case "storage.put_ok":
        this.pending.delete(env.id);
        pending.resolve({
          key: payload.key,
          scope: payload.scope,
          version: payload.version
        });
        return true;
      case "storage.put_conflict": {
        this.pending.delete(env.id);
        const error = new Error(`storage put conflict on ${payload.key}`);
        error.code = "STORAGE_CONFLICT";
        error.currentVersion = payload.current_version ?? null;
        pending.reject(error);
        return true;
      }
      case "storage.del_ok":
        this.pending.delete(env.id);
        pending.resolve(true);
        return true;
      case "storage.scan_page":
        this.pending.delete(env.id);
        pending.resolve({
          entries: payload.entries ?? [],
          cursor: payload.cursor ?? null
        });
        return true;
      case "llm.delta":
        pending.onToken?.(payload.content ?? "");
        return true;
      case "llm.result":
        this.pending.delete(env.id);
        pending.resolve({
          content: payload.content ?? "",
          finishReason: payload.finish_reason ?? null,
          usage: payload.usage ?? null
        });
        return true;
      case "llm.error": {
        this.pending.delete(env.id);
        const error = new Error(payload.message ?? "llm gateway error");
        error.code = payload.code ?? "LLM_GATEWAY_ERROR";
        pending.reject(error);
        return true;
      }
      case "error": {
        this.pending.delete(env.id);
        const error = new Error(payload.message ?? "door protocol error");
        error.code = payload.code ?? "INTERNAL";
        error.details = payload.details ?? null;
        pending.reject(error);
        return true;
      }
      default:
        return false;
    }
  }

  rejectAllPending(error) {
    for (const pending of this.pending.values()) {
      pending.reject(error);
    }
    this.pending.clear();
  }

  async handleAttach(payload) {
    const sessionId = payload.session_id;
    let session = this.sessions.get(sessionId);
    if (!session) {
      session = new DoorSession(this, sessionId);
      this.sessions.set(sessionId, session);
    }
    session.userId = payload.user_id ?? null;
    session.handle = payload.handle ?? "visitor";
    session.role = payload.role ?? "USER";
    session.mode = payload.mode ?? "normal";
    session.viewport = payload.viewport ?? { cols: 80, rows: 40 };
    session.preferences = payload.preferences ?? {};
    session.attachReason = payload.reason ?? "fresh";

    this.send("ready", { session_id: sessionId });
    await this.onAttach?.(session, payload, this);
  }

  async handleTimeTick(payload) {
    const session = this.sessions.get(payload.session_id);
    if (!session) return;
    session.clock = {
      unixTimeSec: payload.unix_time_sec ?? null,
      receivedAtMs: Date.now()
    };
    await this.onTime?.(session, session.clock, payload, this);
  }

  async handleDetach(payload) {
    const session = this.sessions.get(payload.session_id);
    if (!session) return;
    await this.onDetach?.(session, payload.reason ?? "user_left", payload, this);
    this.sessions.delete(payload.session_id);
  }

  async handleShutdown(payload) {
    await this.onShutdown?.(payload, this);
    this.send("goodbye", {});
    this.close();
  }

  request(type, payload) {
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      const ok = this.send(type, payload, id);
      if (!ok) {
        this.pending.delete(id);
        reject(new Error("door websocket is not connected"));
      }
    });
  }

  requestWithTokens(type, payload, onToken) {
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject, onToken });
      const ok = this.send(type, payload, id);
      if (!ok) {
        this.pending.delete(id);
        reject(new Error("door websocket is not connected"));
      }
    });
  }

  connect() {
    if (this.closed) return;
    this.ws = createWebSocket(this.url, PROTOCOL_VERSION);
    onWebSocket(this.ws, "open", () => {
      this.logger?.log?.("door websocket connected");
      this.send("hello", {
        door_id: this.manifest.door_id,
        version: this.manifest.version ?? "0.0.0",
        manifest: this.manifest
      });
    });
    onWebSocket(this.ws, "message", (raw) => this.handleMessage(raw));
    onWebSocket(this.ws, "close", () => {
      this.rejectAllPending(new Error("door websocket closed"));
      this.sessions.clear();
      this.ws = null;
      this.logger?.log?.("door disconnected");
      this.scheduleReconnect();
    });
    onWebSocket(this.ws, "error", (error) => {
      this.logger?.error?.("door websocket error", error);
      this.scheduleReconnect();
    });
  }

  scheduleReconnect() {
    if (this.closed || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.logger?.log?.(`reconnecting door websocket to ${this.url}`);
      this.connect();
    }, this.reconnectDelayMs);
  }
}

function createWebSocket(url, protocol) {
  if (typeof globalThis.WebSocket !== "function") {
    throw new Error("Global WebSocket is unavailable in this Node runtime. Use Node 22+ or provide a WebSocket client.");
  }
  return new globalThis.WebSocket(url, protocol);
}

function isWebSocketOpen(ws) {
  return ws?.readyState === 1;
}

function defaultPlayerEntry(session, input) {
  return {
    label: String(session.handle ?? "YOU").toUpperCase(),
    text: input,
    labelFg: "bright-cyan",
    textFg: "white"
  };
}

function normalizeChoice(value) {
  return String(value ?? "").trim().toLowerCase().replace(/[^a-z0-9]+/g, "");
}

function isEscapeKey(value) {
  const key = String(value ?? "").trim().toLowerCase();
  return key === "escape" || key === "esc";
}

function onWebSocket(ws, type, handler) {
  ws.addEventListener(type, (event) => {
    if (type === "message") {
      handler(event.data);
    } else if (type === "error") {
      handler(event.error ?? event);
    } else {
      handler(event);
    }
  });
}

async function readMessageData(raw) {
  if (typeof raw === "string") return raw;
  if (raw == null) return "";
  if (typeof raw.text === "function") {
    return await raw.text();
  }
  if (raw instanceof ArrayBuffer) {
    return Buffer.from(raw).toString("utf8");
  }
  if (ArrayBuffer.isView(raw)) {
    return Buffer.from(raw.buffer, raw.byteOffset, raw.byteLength).toString("utf8");
  }
  return String(raw);
}

export class DoorSession {
  constructor(client, sessionId) {
    this.client = client;
    this.id = sessionId;
    this.userId = null;
    this.handle = "visitor";
    this.role = "USER";
    this.mode = "normal";
    this.viewport = { cols: 80, rows: 40 };
    this.preferences = {};
    this.attachReason = "fresh";
    this.clock = { unixTimeSec: null, receivedAtMs: null };
    this.data = {};
    this.storage = {
      user: storageScope(client, sessionId, "user"),
      shared: storageScope(client, sessionId, "shared"),
      global: storageScope(client, sessionId, "global")
    };
    this.llm = {
      chat: (messages, { temperature = 0.7, system = null, prompt = null } = {}) =>
        client.request("llm.chat", llmPayload(sessionId, messages, { temperature, system, prompt })),
      stream: (messages, { temperature = 0.7, onToken = null, system = null, prompt = null } = {}) =>
        client.requestWithTokens("llm.stream", llmPayload(sessionId, messages, { temperature, system, prompt }), onToken)
    };

    // Achievement protocol. The door sends id + flavor + points + door_id;
    // the BBS records per (user, door_id, achievement_id) and dedupes
    // server-side. Door-side state typically also dedupes locally. The
    // envelope explicitly tags scope:"door" so the BBS's native Achievements
    // screen can segment door-sourced unlocks from BBS-native ones without
    // having to look at the door_id allow-list. Fire-and-forget; if the BBS
    // hasn't shipped achievement support yet, the envelopes drop silently.
    const doorId = client.manifest?.door_id ?? null;
    this.achievement = {
      unlock: ({ id, title, flavor = null, points = 0, category = null } = {}) => {
        if (typeof id !== "string" || !id.trim()) return false;
        return client.send("achievement.unlock", {
          session_id: sessionId,
          door_id: doorId,
          scope: "door",
          achievement_id: id,
          title: typeof title === "string" ? title : id,
          flavor: typeof flavor === "string" ? flavor : null,
          points: Number.isFinite(points) ? Math.max(0, Math.floor(points)) : 0,
          category: typeof category === "string" ? category : null
        });
      }
    };
  }

  paint(rows, viewportId = "main") {
    this.client.send("paint", {
      session_id: this.id,
      viewport_id: viewportId,
      rows
    });
  }

  paintText(lines, options = {}) {
    this.paint(rowsFromLines(lines, options));
  }

  prompt({ mode = "line", label = null, maxLength = null, validKeys = null } = {}) {
    const payload = {
      session_id: this.id,
      mode
    };
    if (label != null) payload.label = label;
    if (maxLength != null) payload.max_length = maxLength;
    if (validKeys != null) payload.valid_keys = validKeys;
    this.client.send("prompt", payload);
  }

  notify(text, { level = "info", durationMs = 2500 } = {}) {
    this.client.send("notify", {
      session_id: this.id,
      level,
      text,
      duration_ms: durationMs
    });
  }

  effect(kind, params = {}) {
    this.client.send("effect", {
      session_id: this.id,
      kind,
      params
    });
  }

  detach(reason = "completed") {
    this.client.send("detach", {
      session_id: this.id,
      reason
    });
    this.client.sessions.delete(this.id);
  }
}

function llmPayload(sessionId, messages, { temperature, system, prompt }) {
  const payload = {
    session_id: sessionId,
    temperature
  };
  if (Array.isArray(messages) && messages.length > 0) {
    payload.messages = messages;
  } else {
    if (system != null) payload.system = system;
    if (prompt != null) payload.prompt = prompt;
  }
  return payload;
}

function storageScope(client, sessionId, scope) {
  return {
    get(key) {
      return client.request("storage.get", {
        session_id: sessionId,
        scope,
        key
      });
    },
    put(key, value, expectedVersion = null) {
      const payload = {
        session_id: sessionId,
        scope,
        key,
        value
      };
      if (expectedVersion != null) payload.expected_version = expectedVersion;
      return client.request("storage.put", payload);
    },
    del(key) {
      return client.request("storage.del", {
        session_id: sessionId,
        scope,
        key
      });
    },
    scan(prefix = "", { cursor = null, limit = 20 } = {}) {
      const payload = {
        session_id: sessionId,
        scope,
        prefix,
        limit
      };
      if (cursor != null) payload.cursor = cursor;
      return client.request("storage.scan", payload);
    }
  };
}
