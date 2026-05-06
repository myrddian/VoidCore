/**
 * Wraps the localStorage session token under a single key per SPEC §5.
 * Errors swallowed — Safari Private Mode etc. throw on set.
 */
const KEY = "voidcore:session";

export const sessionStore = {
  get(): string | null {
    try { return window.localStorage.getItem(KEY); } catch { return null; }
  },
  set(token: string): void {
    try { window.localStorage.setItem(KEY, token); } catch { /* ignore */ }
  },
  clear(): void {
    try { window.localStorage.removeItem(KEY); } catch { /* ignore */ }
  },
};
