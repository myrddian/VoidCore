/**
 * Entry point per SPEC §6.2 ("The dispatch loop"). Tiny: opens the WS,
 * routes inbound messages to the right module. No application logic — that
 * lives entirely on the server (smart-terminal architecture, SPEC §6).
 */
import { configureThemes, effects } from "./effects.js";
import { envelope } from "./envelope.js";
import { getRegions } from "./layout.js";
import { InputController } from "./input.js";
import { readIntentFromUrl } from "./intent.js";
import { RegionRenderer } from "./region.js";
import { sessionStore } from "./storage.js";
import {
  type AuthOkPayload,
  type AuthErrPayload,
  type Envelope,
  type RegionAppendPayload,
  type RegionClearPayload,
  type RegionNotifyPayload,
  type RegionScrollbackPayload,
  type RegionUpdatePayload,
  type ResumeOkPayload,
  type ResumeErrPayload,
  type InputPromptPayload,
  type EffectOpenUrlPayload,
  type EffectPlaySoundPayload,
  type EffectSetTitlePayload,
  type EffectCopyClipboardPayload,
  type EffectSetThemePayload,
} from "./types.js";
import { WsClient, sendRaw, registerInstance } from "./ws.js";
import { renderTree, type RenderDeps } from "./widgets/tree.js";
import { stopActiveEditor, getActiveEditorId } from "./widgets/editor/editor.js";
import type { Element } from "./widgets/element-types.js";

function findEditorId(el: Element, id: string): boolean {
  if (el.kind === "editor" && el.id === id) return true;
  if (el.kind === "vstack" || el.kind === "form") {
    return el.children.some(c => findEditorId(c, id));
  }
  if (el.kind === "padded" || el.kind === "styled") return findEditorId(el.child, id);
  return false;
}

interface ThemeBootstrapPayload {
  knownThemes?: string[];
  labels?: Record<string, string>;
  overlayCss?: string;
}

async function loadThemes(): Promise<void> {
  try {
    const response = await fetch("/api/instance/themes", {
      headers: { Accept: "application/json" },
    });
    if (!response.ok) return;
    const payload = (await response.json()) as ThemeBootstrapPayload;
    configureThemes(payload.knownThemes ?? []);
    injectOverlayThemeCss(payload.overlayCss ?? "");
  } catch (_) {
    // Startup should remain resilient when the theme manifest endpoint
    // isn't reachable yet or an older server build doesn't provide it.
  }
}

function injectOverlayThemeCss(css: string): void {
  const id = "voidcore-overlay-themes";
  let style = document.getElementById(id) as HTMLStyleElement | null;
  if (style === null) {
    style = document.createElement("style");
    style.id = id;
    document.head.appendChild(style);
  }
  style.textContent = css;
}

async function main(): Promise<void> {
  await loadThemes();
  const regions = getRegions();
  const renderer = new RegionRenderer(regions);

  const statusInput = document.getElementById("status-input") as HTMLInputElement;
  const statusText = document.getElementById("status-text") as HTMLElement;
  const cursor = document.getElementById("cursor") as HTMLElement;

  const input = new InputController(statusText, statusInput, cursor, {
    onKeystroke: (key) => ws.send(envelope("keystroke", { key })),
    onLineSubmit: (text) => ws.send(envelope("line.submit", { text })),
    onLineCancel: () => ws.send(envelope("line.cancel", {})),
  });

  const wsUrl = wsUrlFor(window.location);

  const ws = new WsClient(wsUrl, {
    onStatus: (text) => input.setStatusText(text),
    getRegionVersions: () => renderer.versions(),
    getStoredToken: () => sessionStore.get(),
    getIntent: () => readIntentFromUrl(),
    onMessage: (env) => dispatch(env),
    onServerClose: () => {
      // Goodbye (or any deliberate server close) — drop the persisted
      // token so a refresh starts fresh, and stay on whatever was last
      // painted (the NO CARRIER frame the server sent before closing).
      sessionStore.clear();
      input.setStatusText("");
    },
  });

  function dispatch(env: Envelope): void {
    switch (env.type) {
      case "screen.define":
        // v1 always emits the default layout — accept and ignore for now,
        // forward-compat scaffold per ROADMAP / ADR-017.
        break;
      case "region.update": {
        const payload = env.payload as RegionUpdatePayload;
        if (payload.tree) {
          const tree = payload.tree as Element;
          if (payload.region === "main") {
            // Tear down active editor if it's not present in the incoming tree.
            // renderEditor handles the same-id reuse case; this catches removal.
            const edId = getActiveEditorId();
            if (edId !== null && !findEditorId(tree, edId)) {
              stopActiveEditor();
            }
          }
          const target = document.getElementById(`region-${payload.region}`);
          if (target) {
            const deps: RenderDeps = {
              sendMessage: (msg) => sendRaw(msg),
              getCurrentTheme: () =>
                document.body.getAttribute("data-theme") ?? "phosphor",
              setStatusBar: (text) => input.setStatusText(text),
            };
            target.replaceChildren(
              renderTree(payload.tree, payload.focus ?? null, deps),
            );
            renderer.refreshResponsiveLayout();
          }
        } else {
          // rows-mode: tear down any active editor when main region goes back to rows
          if (payload.region === "main") stopActiveEditor();
          renderer.update(payload);
        }
        break;
      }
      case "region.append":
        renderer.append(env.payload as RegionAppendPayload);
        break;
      case "region.scrollback":
        renderer.scrollback(env.payload as RegionScrollbackPayload);
        break;
      case "region.clear":
        renderer.clear(env.payload as RegionClearPayload);
        break;
      case "region.notify":
        renderer.notify(env.payload as RegionNotifyPayload);
        break;
      case "input.prompt":
        input.setMode(env.payload as InputPromptPayload);
        break;
      case "input.cancel":
        input.cancel();
        break;
      case "effect.open_url":
        effects.openUrl(env.payload as EffectOpenUrlPayload);
        break;
      case "effect.play_sound":
        effects.playSound(env.payload as EffectPlaySoundPayload);
        break;
      case "effect.set_title":
        effects.setTitle(env.payload as EffectSetTitlePayload);
        break;
      case "effect.copy_clipboard":
        effects.copyClipboard(env.payload as EffectCopyClipboardPayload);
        break;
      case "effect.set_theme":
        effects.setTheme(env.payload as EffectSetThemePayload);
        break;
      case "auth.ok": {
        const p = env.payload as AuthOkPayload;
        // Token comes via auth.ok.user — but the spec puts the session token
        // delivery on auth.ok response too. We expect a property; if absent,
        // the server is responsible for not requiring resume yet.
        const tokenFromAuth = (env.payload as { token?: string }).token;
        if (tokenFromAuth) sessionStore.set(tokenFromAuth);
        if (p.user) document.title = `VOIDcore — ${p.user.handle}`;
        break;
      }
      case "auth.err": {
        const p = env.payload as AuthErrPayload;
        console.warn("auth.err", p);
        break;
      }
      case "resume.ok": {
        const p = env.payload as ResumeOkPayload;
        if (!p.sync && p.frames) for (const f of p.frames) dispatch(f);
        break;
      }
      case "resume.err": {
        const p = env.payload as ResumeErrPayload;
        console.warn("session expired:", p.code, p.message);
        sessionStore.clear();
        // The session is dead server-side. Reload the page to land on the
        // login screen with a clean slate. Brief delay so any final paint
        // has a chance to show first.
        try {
          document.body.dispatchEvent(new CustomEvent("voidcore-session-expired",
              { detail: { code: p.code, message: p.message } }));
        } catch (_) { /* CustomEvent not supported in some test envs — skip. */ }
        setTimeout(() => location.reload(), 800);
        break;
      }
      case "system.heartbeat":
        // Application-level liveness signal — handled in ws.ts watchdog
        // by virtue of arriving as a message (resets last-frame timestamp).
        // Nothing to render. Server sends one per heartbeat tick.
        break;
      case "error":
        console.warn("server error", env.payload);
        break;
      default:
        console.warn("unknown server type:", env.type);
    }
  }

  registerInstance(ws);
  ws.start();
}

function wsUrlFor(loc: Location): string {
  const proto = loc.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${loc.host}/ws`;
}

void main();
