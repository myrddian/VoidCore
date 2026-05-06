/**
 * Wire-protocol types matching the server-side sealed records (#14).
 * These are the canonical shapes the client paints from / sends.
 */

import type { Element } from "./widgets/element-types.js";

export const PROTOCOL_VERSION = "voidcore-node-v1";

export interface Envelope<P = unknown> {
  id: string | null;
  type: string;
  protocol_version: string;
  seq: number;
  mac: string | null;
  payload: P;
}

// --- Cell content (SPEC §4.4) ----------------------------------------------

export interface Span {
  text: string;
  fg?: string;
  bg?: string;
  bold?: boolean;
}

export interface Row {
  row: number;
  spans: Span[];
}

export interface Cursor {
  row: number;
  col: number;
}

// --- Server message payloads (SPEC §4.3) -----------------------------------

export interface ScreenDefinePayload {
  id: string;
  version: number;
  layout: { name: string };
  cacheable?: boolean;
  ttl_seconds?: number;
}

export interface RegionUpdatePayload {
  region: string;
  version: number;
  content: Row[];
  cursor?: Cursor | null;
  // NEW — tree-mode fields. Mutually exclusive with content/cursor/mode.
  tree?: Element;
  focus?: string | null;
}

export interface RegionAppendPayload {
  region: string;
  version: number;
  content: Row[];
}

export interface RegionScrollbackPayload {
  region: string;
  before_seq: number;
  content: Row[];
}

export interface RegionClearPayload {
  region: string;
}

export type NotifyLevel = "info" | "warn" | "alert";

export interface RegionNotifyPayload {
  region: string;
  content: Row[];
  duration_ms?: number;
  level?: NotifyLevel;
}

export type InputMode = "none" | "keystroke" | "line" | "password";

export interface InputPromptPayload {
  mode: InputMode;
  label?: string;
  max_length?: number;
  valid_keys?: string;
  initial?: string;
}

// --- Effects ---------------------------------------------------------------

export interface EffectOpenUrlPayload      { url: string; }
export interface EffectPlaySoundPayload    { name: string; }
export interface EffectSetTitlePayload     { title: string; }
export interface EffectCopyClipboardPayload { text: string; }
export interface EffectSetThemePayload     { name: string; }

// --- Auth ------------------------------------------------------------------

export interface UserSummary {
  id: number;
  handle: string;
  is_sysop: boolean;
}

export interface AuthOkPayload  { user: UserSummary; intent_resolved?: string; }
export interface AuthErrPayload { code: string; message: string; field?: string; }

export interface ResumeOkPayload  { sync: boolean; frames?: Envelope[] | null; }
export interface ResumeErrPayload { code: string; message: string; }

export interface ProtocolErrorPayload {
  code: string;
  message: string;
  ref_id?: string;
  details?: Record<string, unknown>;
}
