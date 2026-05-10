/**
 * Side-effect handlers per SPEC §4.3 ("Server-to-client side-effect messages").
 * Closed set, no generic client_cmd envelope.
 */
import {
  type EffectCopyClipboardPayload,
  type EffectOpenUrlPayload,
  type EffectPlaySoundPayload,
  type EffectSetThemePayload,
  type EffectSetTitlePayload,
} from "./types.js";

const BUILT_IN_THEMES = ["phosphor", "amber", "cga", "modern"];
const knownThemes = new Set(BUILT_IN_THEMES);

export function configureThemes(themeNames: string[] | null | undefined): void {
  knownThemes.clear();
  for (const name of themeNames ?? BUILT_IN_THEMES) {
    const normalized = name.trim().toLowerCase();
    if (normalized) knownThemes.add(normalized);
  }
  if (knownThemes.size === 0) {
    for (const name of BUILT_IN_THEMES) knownThemes.add(name);
  }
}

export const effects = {
  openUrl(p: EffectOpenUrlPayload): void {
    // noopener for security; new tab keeps the BBS session alive in this one.
    window.open(p.url, "_blank", "noopener,noreferrer");
  },

  playSound(p: EffectPlaySoundPayload): void {
    // v1 has no asset bundle; sound names are reserved and silent for now.
    // The contract is set so the file area's "modem connect" sting can land
    // later without a protocol change.
    void p;
  },

  setTitle(p: EffectSetTitlePayload): void {
    document.title = p.title;
  },

  copyClipboard(p: EffectCopyClipboardPayload): void {
    navigator.clipboard?.writeText(p.text).catch(() => {
      // Clipboard access is gated by browser policy (must be same-origin,
      // user-gestured). Failing silently is acceptable — the effect is
      // a convenience, not a correctness requirement.
    });
  },

  setTheme(p: EffectSetThemePayload): void {
    const name = (p.name ?? "phosphor").toLowerCase();
    const theme = knownThemes.has(name) ? name : "phosphor";
    document.body.setAttribute("data-theme", theme);
  },
};
