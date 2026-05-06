/**
 * Input mode state machine per SPEC §6.4 / §6.5:
 *
 *   none      — no input. cursor hidden.
 *   keystroke — single keypress; client filters by valid_keys, drops
 *               invalid silently. Sends keystroke { key } immediately.
 *               Status row shows '> X' echo for instant feedback.
 *   line      — full-line input with local echo + backspace + Enter/Esc.
 *   password  — same as line but echoes '*'; buffer wiped after submit.
 */
import { type InputPromptPayload } from "./types.js";

export interface InputCallbacks {
  onKeystroke: (key: string) => void;
  onLineSubmit: (text: string) => void;
  onLineCancel: () => void;
}

export class InputController {

  private mode: InputPromptPayload["mode"] = "none";
  private label = "";
  private maxLength = 0;
  private validKeys = "";
  private buffer = "";
  private suppressRefocus = false;

  constructor(
    private readonly statusText: HTMLElement,
    private readonly statusInput: HTMLInputElement,
    private readonly cursor: HTMLElement,
    private readonly cb: InputCallbacks,
  ) {
    // keydown lives on document so keystroke mode (where the input element
    // is display:none and unfocusable) still captures presses. line/password
    // mode also work — keydown bubbles from the focused input up to document.
    document.addEventListener("keydown", this.handleKeyDown);
    // input event only fires on the actual element being typed into; that's
    // exactly what we want for line/password buffer sync.
    statusInput.addEventListener("input", this.handleInputEvent);
    // When the user clicks anywhere except the input (e.g. dragging across
    // chat text to select), suppress the refocus so the selection isn't
    // collapsed by .focus(). Lifted on the next keydown — typing always
    // brings the input back online without the user having to click anything.
    document.addEventListener("mousedown", (ev) => {
      if (ev.target !== statusInput) this.suppressRefocus = true;
    });
    statusInput.addEventListener("blur", () => {
      if (this.suppressRefocus) return;
      if (this.mode !== "line" && this.mode !== "password") return;
      setTimeout(() => statusInput.focus(), 0);
    });
  }

  setMode(p: InputPromptPayload): void {
    // Diagnostic — paired with the keydown log so we can see the prompt
    // → mode lifecycle in the console when investigating "nothing fires".
    // eslint-disable-next-line no-console
    console.debug("[input] setMode", p);
    this.mode = p.mode;
    this.label = p.label ?? "";
    this.maxLength = p.max_length ?? 0;
    this.validKeys = p.valid_keys ?? "";
    this.buffer = p.initial ?? "";
    this.render();

    if (p.mode === "none") {
      this.statusInput.blur();
    } else {
      // Always focus, including in keystroke mode. The CSS keeps the input
      // off-screen-but-focusable when hidden so mobile users still get the
      // on-screen keyboard (#42).
      this.statusInput.focus();
    }
  }

  cancel(): void {
    this.mode = "none";
    this.buffer = "";
    this.label = "";
    this.render();
  }

  /** Caller (ws.ts) writes this to the status row when reconnecting. */
  setStatusText(text: string): void {
    this.writeStatusText(text);
  }

  private render(): void {
    if (this.mode === "none") {
      this.writeStatusText("");
      this.statusInput.classList.add("hidden");
      this.cursor.classList.add("hidden");
      this.statusInput.value = "";
      return;
    }
    this.writeStatusText(this.label ? `${this.label} ` : "");
    if (this.mode === "keystroke") {
      // Keystroke mode: input is hidden and reordered to the end of the
      // flex row, so .cursor sits right after the prompt. Show it.
      this.statusInput.classList.add("hidden");
      this.statusInput.value = "";
      this.cursor.classList.remove("hidden");
    } else {
      // Line/password: hide our block cursor and let the native caret do
      // the work — it tracks the typed value position, no flex math needed.
      this.statusInput.classList.remove("hidden");
      this.statusInput.value = this.echoFor(this.buffer);
      this.statusInput.setSelectionRange(this.statusInput.value.length, this.statusInput.value.length);
      this.cursor.classList.add("hidden");
    }
  }

  private echoFor(text: string): string {
    return this.mode === "password" ? "*".repeat(text.length) : text;
  }

  private handleKeyDown = (ev: KeyboardEvent): void => {
    // Diagnostic: log every keydown the controller sees, plus its current
    // mode. Helps debug "nothing fires" reports — if the log is silent
    // when a key is pressed, the event isn't reaching this handler at all
    // (focus stolen by another listener, dev-tools open, etc.). If the log
    // shows the key but mode="none", the server hasn't sent an input.prompt
    // for the current screen (or sent one with mode=none).
    // eslint-disable-next-line no-console
    console.debug("[input] keydown", { key: ev.key, mode: this.mode, validKeys: this.validKeys });
    // Always let OS shortcuts (Cmd/Ctrl combos) through — copy/paste/refresh/
    // devtools/etc. should work regardless of input mode. Without this, our
    // preventDefault() in keystroke mode swallows Cmd+C and the user can't
    // copy posted links or NFO text.
    if (ev.metaKey || ev.ctrlKey) {
      return;
    }
    // The first keystroke after a click-to-select releases the refocus
    // suppression and brings the input back online so typing resumes.
    if (this.suppressRefocus) {
      this.suppressRefocus = false;
      if ((this.mode === "line" || this.mode === "password") &&
          document.activeElement !== this.statusInput) {
        this.statusInput.focus();
      }
    }
    if (this.mode === "none") {
      ev.preventDefault();
      return;
    }
    if (this.mode === "keystroke") {
      ev.preventDefault();
      // Esc has universal "back/cancel" semantics — bypass the valid_keys
      // filter and fire onLineCancel (same shape as line/password mode below)
      // so the server's screen.onCancel handler runs. Without this, screens
      // whose valid_keys don't include "Escape" (i.e. all of them) leave the
      // user with no Esc fallback when [Q] doesn't work for any reason.
      if (ev.key === "Escape") {
        // eslint-disable-next-line no-console
        console.debug("[input] dispatch onLineCancel (Esc in keystroke)");
        this.cb.onLineCancel();
        return;
      }
      const key = normaliseKey(ev.key);
      if (!key) {
        // eslint-disable-next-line no-console
        console.debug("[input] keystroke filtered: unknown key", ev.key);
        return;
      }
      // valid_keys is uppercase per SPEC §4.3 ('FBMCOULDNWG' style); we match
      // case-insensitively but pass the canonical uppercase form to the server.
      if (this.validKeys && !this.validKeys.toUpperCase().includes(key.toUpperCase())) {
        // eslint-disable-next-line no-console
        console.debug("[input] keystroke filtered: not in valid_keys",
                      { key, validKeys: this.validKeys });
        return;
      }
      if (this.validKeys && this.validKeys.length > 0) {
        this.writeStatusText(`${this.label ? this.label + " " : ""}> ${key}`);
      }
      // eslint-disable-next-line no-console
      console.debug("[input] dispatch onKeystroke", key);
      this.cb.onKeystroke(key);
      return;
    }

    // line / password
    if (ev.key === "Enter") {
      ev.preventDefault();
      const out = this.buffer;
      this.buffer = "";
      this.statusInput.value = "";
      this.cb.onLineSubmit(out);
      return;
    }
    if (ev.key === "Escape") {
      ev.preventDefault();
      this.buffer = "";
      this.statusInput.value = "";
      this.cb.onLineCancel();
      return;
    }
    // Allow native handling for everything else; handleInputEvent syncs buffer.
  };

  private handleInputEvent = (): void => {
    if (this.mode === "keystroke") {
      // Keystroke mode preventDefault's valid keys, but invalid ones can
      // still slip through into input.value. Wipe so the (focusable but
      // off-screen) input doesn't accumulate junk.
      this.statusInput.value = "";
      return;
    }
    if (this.mode !== "line" && this.mode !== "password") return;
    if (this.mode === "password") {
      // For password mode, the input element shows '*'s; we keep our own
      // plaintext buffer in sync by detecting length change.
      const visibleLen = this.statusInput.value.length;
      if (visibleLen < this.buffer.length) {
        // user deleted some chars
        this.buffer = this.buffer.slice(0, visibleLen);
      } else if (visibleLen > this.buffer.length) {
        // user typed a new char (or pasted) — recover by reading the literal
        // characters they just inserted and discarding the '*' echo.
        // Simplest robust path: read the input element's last N chars.
        const added = this.statusInput.value.slice(this.buffer.length);
        // Replace any '*' (which would only appear via paste of literal '*')
        // with itself; for normal typing this is just the new key.
        this.buffer += added;
      }
      this.statusInput.value = "*".repeat(this.buffer.length);
      this.statusInput.setSelectionRange(this.buffer.length, this.buffer.length);
      this.enforceMaxLength();
    } else {
      this.buffer = this.statusInput.value;
      this.enforceMaxLength();
      this.statusInput.value = this.buffer;
    }
  };

  private enforceMaxLength(): void {
    if (this.maxLength > 0 && this.buffer.length > this.maxLength) {
      this.buffer = this.buffer.slice(0, this.maxLength);
    }
  }

  private writeStatusText(text: string): void {
    this.statusText.replaceChildren();
    if (!text) return;
    const re = /\[([^\]]+)\]/g;
    let lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = re.exec(text)) !== null) {
      if (match.index > lastIndex) {
        this.statusText.appendChild(
          document.createTextNode(text.slice(lastIndex, match.index)));
      }
      const open = document.createElement("span");
      open.className = "status-bracket";
      open.textContent = "[";
      const key = document.createElement("span");
      key.className = "status-key";
      key.textContent = match[1] ?? "";
      const close = document.createElement("span");
      close.className = "status-bracket";
      close.textContent = "]";
      this.statusText.append(open, key, close);
      lastIndex = match.index + match[0].length;
    }
    if (lastIndex < text.length) {
      this.statusText.appendChild(document.createTextNode(text.slice(lastIndex)));
    }
  }
}

/** Map browser key names to the canonical 1–4 char string the server expects. */
function normaliseKey(key: string): string | null {
  // Single printable character
  if (key.length === 1) return key;
  // Named keys we want to forward verbatim
  const named = new Set(["Enter", "Escape", "Tab", "Backspace", "ArrowUp",
    "ArrowDown", "ArrowLeft", "ArrowRight", "PageUp", "PageDown", "Home", "End"]);
  if (named.has(key)) return key;
  return null;
}
