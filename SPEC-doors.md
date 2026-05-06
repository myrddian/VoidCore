# VOIDcore — Door Protocol Specification (`voidcore-door-v1`)

**Version:** 1.0
**Status:** Draft for implementation
**Companion to:** `SPEC.md` (BBS↔client protocol, `voidcore-node-v1`)
**Decisions referenced:** ADR-019, ADR-020 in `DECISIONS.md`

> Doors in v1 are protocol-mediated. The user never talks to a door
> directly — the BBS proxies every message and mediates rendering.
> This contract document defines the door↔BBS WebSocket protocol
> (`voidcore-door-v1`), independent of language and transport details.
> The browser keeps speaking the normal BBS client protocol
> (`voidcore-node-v1`) to the BBS; the door protocol is the server-side
> extension surface that the BBS adapts into that existing render
> model.
>
> v1 implements **Normal mode only** (cooperative — door owns a
> viewport, BBS owns notifications + reserved keystrokes). The
> protocol carries the full message surface for **Deferred mode**
> (sovereign — door owns the screen) but the BBS rejects
> `mode: deferred` in `attach{}` until v2. The DOS-shim wrapper that
> runs old DOS doors via DOSBox is a v3+ concern and depends on
> Deferred mode shipping first.

---

## 1. Concept

A door is a stateful service that delivers an interactive experience to
one or more BBS users at a time. Examples: a guess-the-number game, a
multiplayer trade simulator, a daily Wordle clone, a notebook of sysop
tools, a real-time poll.

Doors are *not* part of the BBS process by default. They register with
the BBS as services and communicate over WebSocket. A door can be
co-located in the BBS JVM (the in-process variant of the SDK is just a
direct method call) or run in a separate process — same protocol either
way.

### Two interaction modes (one implemented in v1)

**Normal mode (v1):** The door is mounted into the BBS's primary
application surface — the current `main` render region. Practically,
this means the door sits where a `Screen` / `ScreenApp` would normally
paint. The BBS minimises banner chrome to reclaim vertical space, then
grants the door a *viewport* inside that main region. The door paints
into the viewport; the BBS keeps owning the shell around it:
notifications, theme/application effects, mention popups, reserved
keystrokes (Esc-to-leave, theme cycle), and session lifecycle. User
input is filtered through the BBS first — the door sees only keys and
lines the BBS chose to forward.

**Deferred mode (specced, not implemented in v1):** The door owns the
whole screen. BBS becomes a pipe. Two privileges remain server-side:
(a) a force-exit keystroke the door cannot bind, (b) the right to
overlay an urgent notification, suspending the door. This mode exists
for screen-takeover doors (graphical apps, future DOS-game wrappers).

`attach{mode: "deferred"}` is rejected by the BBS in v1 with
`MODE_NOT_SUPPORTED`. The wire surface is reserved so v2 doesn't need
a new protocol version.

---

## 2. Architecture

```
[browser] ──voidcore-node-v1──▶ [BBS]
                                │
                                ├──voidcore-door-v1──▶ [door A: in-JVM]
                                ├──voidcore-door-v1──▶ [door B: Python sidecar]
                                └──voidcore-door-v1──▶ [door C: DOS-shim]   (v3+)
```

### Relationship to the current V1 client protocol

Doors do not define a second browser-facing UI protocol. The browser
continues speaking `voidcore-node-v1` to the BBS exactly as it does for
every other screen. The BBS, in turn:

1. receives `voidcore-door-v1` messages from the door,
2. validates and mediates them, then
3. projects the door's output into the existing V1 render model.

In Normal mode this projection target is the primary `main` render
surface, with banner rows minimised to create as much usable door space
as possible. From the browser's perspective, a door session is still
"just the BBS" — the BBS remains the only thing the client talks to.

### One WebSocket per door, multiplexed across users

Each door has *one* WebSocket connection to the BBS, regardless of how
many users are simultaneously playing. Every message carries a
`session_id` so the door knows which user it relates to. See ADR-019
for the rationale; the short version is: a multiplayer door is the
common case once you have any door at all, and per-user connections
make the door multi-process its own state. Multiplex makes the
"one service serving N users" reality explicit.

A door MAY hold up to `max_concurrent_sessions` (default: 64,
overridable per-manifest) attached sessions at once. If a new
`attach{}` would exceed the limit, the BBS rejects it with
`DOOR_BUSY`.

### Co-located vs networked transport

Same protocol, different transport:

- **In-process (Java SDK):** door implements an interface. The SDK
  serialises the same messages via direct method calls; no real WS
  is opened. The door can be hot-swapped at JVM restart.
- **Networked (sidecar):** door is a separate process speaking
  `voidcore-door-v1` over a real WS. Spawn-on-demand or pre-running. BBS
  reaches it on a configured URL (typically `ws://<host>:<port>/door`).

The SPEC is identical for both. Door authors write against the
protocol, not the transport.

### Trust and isolation

Doors are *partially trusted*. They run in the BBS's JVM (in-process
variant) or as authorised services on the same network (networked).
They are not adversarial in the way an end user is — the operator
chose to install them — but the BBS still:

1. Validates every door message against the wire schema.
2. Enforces "door can only address sessions it currently has attached"
   — a door cannot paint into a session it never `attach{}`'d, nor
   forge keystrokes from another user.
3. Substitutes server-side identity (user_id) for `scope: "user"` KV
   operations — the door cannot read another user's state by passing
   a different user_id.
4. Drops messages addressed to detached/unknown sessions silently
   (idempotent).
5. Rate-limits door→BBS messages per session to prevent a runaway
   door from saturating a user's screen.

---

## 3. Manifest

A door registers itself with the BBS at startup (in-process: read at
class load; networked: posted on first `hello{}`). The manifest
declares identity, capability needs, storage usage, and operational
limits.

```yaml
# Door manifest (canonical YAML; in-process doors return the same shape
# as a record from the SDK)
door_id: guess-the-number          # stable slug — used as KV namespace
name: "Guess the Number"           # display name in door menu
version: "1.0.0"
authors: ["sysop"]
description: "Pick a number 1-100, BBS thinks of one. Six guesses."

# Modes the door can run in. v1 BBS only honours "normal".
modes_supported: [normal]
default_mode: normal

# Display requirements. BBS may grant a smaller viewport — door must
# cope or refuse via reject_attach.
viewport:
  min: { cols: 40, rows: 12 }
  preferred: { cols: 60, rows: 20 }

# Capabilities the door wants. BBS grants based on door trust level
# and operator config.
capabilities:
  storage_kv: true                 # use BBS KV?  see §6
  notifications: true              # may post BBS-region notifications
  multi_session: false             # can hold >1 attached session
  inter_session_messages: false    # cross-session messaging within door
  user_handle_visible: true        # door reads user.handle
  user_id_visible: true            # door reads user_id (stable identifier)

# Concurrency limits.
max_concurrent_sessions: 8

# What the door uses for persistent state.
storage_used: bbs-kv               # bbs-kv | own | none
storage_quota_bytes: 1048576       # advisory; BBS may enforce

# Idle / lifecycle.
idle_timeout_sec: 600              # BBS detaches sessions after this
hibernate_after_sec: 1800          # BBS may stop a sidecar door process
```

The BBS rejects manifests it can't honour with
`MANIFEST_INCOMPATIBLE` and a reason.

---

## 4. Connection lifecycle

### 4.1 Registration

A networked door connects, sends `hello{}`, and waits for `welcome{}`:

```
door → BBS:  hello { door_id, version, manifest }
BBS  → door: welcome { protocol_version, door_session_token,
                        capabilities_granted, kv_quota_bytes,
                        max_concurrent_sessions_granted }
                  | manifest_rejected { code, reason }
```

In-process doors skip this — the SDK registers them directly via the
`DoorRegistry` bean.

`welcome.capabilities_granted` may be a strict subset of what the
manifest asked for. The door must accept that or close the WS.

`door_session_token` is opaque; the door sends it on every subsequent
message (transport-level identifier; not the same as `session_id`,
which identifies one *user*'s session within the door).

### 4.2 Attach (per user)

When a user picks the door from the BBS door menu:

```
BBS  → door: attach { session_id, user_id, handle, role,
                      mode: "normal" | "deferred",
                      viewport: { cols, rows },
                      preferences: { theme, locale } }
door → BBS:  ready { session_id }
                  | reject_attach { session_id, code, reason }
```

`session_id` is stable for the lifetime of the user's interaction with
this door. It is opaque to the door; just a key.

A door MUST handle `attach{}` for a `session_id` it already holds as a
*reattach* (e.g. after a BBS restart or door process restart) by
restoring state from KV (or its own store) and emitting an immediate
`paint{}`. The reattach signal is `attach.reason: "reconnect"` set by
the BBS.

If the door rejects (`reject_attach`), the BBS shows the user the
provided reason and returns them to the door menu.

### 4.3 Active session

While a session is attached:

```
BBS  → door: input.key  { session_id, key, modifiers }
BBS  → door: input.line { session_id, text }
BBS  → door: time.tick  { session_id, unix_time_sec }    # optional wall-clock sync
BBS  → door: viewport_resize { session_id, cols, rows }   # rare
BBS  → door: suspend { session_id, reason }               # deferred only
BBS  → door: resume  { session_id }                       # deferred only

door → BBS:  paint  { session_id, viewport_id, rows[] }
door → BBS:  prompt { session_id, mode, label, max_length, valid_keys }
door → BBS:  notify { session_id, level, text, duration_ms }
door → BBS:  effect { session_id, kind, params }          # play_sfx, etc.
door → BBS:  storage.* { session_id, scope, key, ... }    # see §6
door → BBS:  detach { session_id, reason }                # door-initiated
```

`time.tick{}` is a low-frequency server-driven clock hint for doors that
want synchronized timers, room clocks, or animation pacing without
inventing their own time source. It carries Unix time in whole seconds and
is intentionally coarse; operators should keep it at **5 seconds or
higher**, not as a high-rate timestamp on every message.

`rows[]` payload is identical to `voidcore-node-v1`'s `Row`/`Span` shape
(SPEC.md §4.4). The BBS validates it, may colour-clamp, and forwards
the row into the user's `region-main` (viewport-offset applied).

### 4.4 Detach

Either side can initiate:

```
BBS  → door: detach { session_id, reason: "user_left" | "timeout" |
                                            "kicked" | "shutdown" }
door → BBS:  detach { session_id, reason: "completed" | "error" |
                                            "self_kicked" }
```

After a `detach{}` is exchanged, neither side may send messages tagged
with that `session_id`. Late messages are dropped silently.

### 4.5 Door shutdown

```
BBS  → door: shutdown { reason, grace_sec }
door → BBS:  goodbye {}
```

BBS sends `detach{}` for every still-attached session before
`shutdown{}`. Door has `grace_sec` to flush KV writes and respond
with `goodbye{}`, then BBS closes the WS.

---

## 5. Wire envelope

Every message is a JSON object wrapped in the same envelope as
`voidcore-node-v1` (SPEC.md §4.2):

```json
{
  "id": "uuid-or-null",
  "type": "namespaced.message.type",
  "protocol_version": "voidcore-door-v1",
  "seq": 0,
  "mac": null,
  "payload": { ... }
}
```

`seq` and `mac` are reserved for v2 (mirrors ADR-018 in the user
protocol). v1 leaves them at `0` / `null`.

`type` namespace is flat: `hello`, `welcome`, `attach`, `paint`,
`storage.put`, etc. No nested objects in `type`.

---

## 6. Storage: BBS-provided KV

Doors that opt in via `capabilities.storage_kv: true` get a namespaced
key-value store backed by Postgres. ADR-020 explains the rationale.

### 6.1 Scopes

| Scope | Identity injected by BBS | Use case |
|---|---|---|
| `user` | `(door_id, user_id)` from current `session_id` | Per-player save |
| `shared` | `(door_id)` | Multiplayer game state |
| `global` | `(door_id)` | Leaderboards, MOTD |

A door cannot pass a `user_id` to address another user's state — the
BBS substitutes from the current `session_id`. For cross-user
coordination, doors use `shared` or `global`.

### 6.2 Operations

```
door → BBS:  storage.get  { session_id, scope, key }
BBS  → door: storage.value { session_id, scope, key, value, version }
                          | storage.miss { session_id, scope, key }

door → BBS:  storage.put  { session_id, scope, key, value, expected_version? }
BBS  → door: storage.put_ok { session_id, scope, key, version }
                          | storage.put_conflict { session_id, scope, key,
                                                    current_version }

door → BBS:  storage.del  { session_id, scope, key }
BBS  → door: storage.del_ok { session_id }

door → BBS:  storage.scan { session_id, scope, prefix, cursor?, limit? }
BBS  → door: storage.scan_page { session_id, entries[], cursor? }
```

`expected_version` enables compare-and-swap. Omit for last-write-wins.

`value` is opaque JSON — door's responsibility to interpret. Max
1MB per value (advisory v1, enforced if anything goes pathological).

### 6.3 Schema

```sql
CREATE TABLE door_state (
  door_id     TEXT NOT NULL,
  scope       TEXT NOT NULL CHECK (scope IN ('user', 'shared', 'global')),
  scope_key   TEXT NOT NULL,                -- user_id::text for 'user'; '' otherwise
  key         TEXT NOT NULL,
  value       JSONB NOT NULL,
  version     BIGINT NOT NULL DEFAULT 1,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (door_id, scope, scope_key, key)
);
CREATE INDEX door_state_scan ON door_state (door_id, scope, scope_key, key text_pattern_ops);
```

`version` increments on every write. Reads return current version; CAS
PUT `WHERE version = expected_version` and bumps.

### 6.4 Backup

`door_state` rolls into the same pgBackRest stanza as the rest of the
schema. Operators get door state backups for free (ADR-010 + ADR-020).

### 6.5 BBS-mediated LLM gateway

Doors may opt into a BBS-owned LLM gateway via
`capabilities.llm: true` in the manifest. When granted, the door can
request text completions without holding provider credentials or
talking to model vendors directly. The BBS remains the policy and
transport boundary: model selection, auth, rate limiting, retries,
timeouts, and auditability live server-side.

Blocking request:

```
door → BBS:  llm.chat { session_id, messages[], temperature? }
BBS  → door: llm.result { session_id, content, finish_reason?, usage? }
          | llm.error  { session_id, code, message }
```

Streaming request:

```
door → BBS:  llm.stream { session_id, messages[], temperature? }
BBS  → door: llm.delta  { session_id, content }
          | llm.result { session_id, content, finish_reason?, usage? }
          | llm.error  { session_id, code, message }
```

`messages[]` is OpenAI-style:

```json
[
  { "role": "system", "content": "You are a terse noir terminal." },
  { "role": "user",   "content": "Summarise the alley scene." }
]
```

Envelope `id` is the correlation key for both blocking and streaming
calls. The BBS may also accept the convenience shape
`{ system, prompt }` and normalise it into the message list.

---

## 7. Reserved keystrokes

The BBS *intercepts* the following keys before forwarding any
`input.key{}` to the door. A door can never bind these:

| Key | Mode | Action |
|---|---|---|
| `Esc` | Normal | Leave the door, return to door menu |
| `Ctrl+]` | Normal + Deferred | Force-exit (for misbehaving doors) |
| `Ctrl+T` | Normal + Deferred | Toggle theme (BBS-level) |

Deferred mode (v2) loses `Esc` as a reserved key — Deferred doors take
the whole keyboard *except* `Ctrl+]`. Normal mode keeps both reserved.

The BBS logs a warning if a door's manifest tries to declare a binding
for any reserved key.

---

## 8. Error codes

Door-side errors that close the session (BBS → door, `error{}`
message):

| Code | Meaning |
|---|---|
| `MANIFEST_INCOMPATIBLE` | Manifest declares features the BBS won't grant |
| `MODE_NOT_SUPPORTED` | Door requested `deferred`; v1 doesn't implement it |
| `DOOR_BUSY` | Door at `max_concurrent_sessions`; reject `attach` |
| `INVALID_VIEWPORT` | Door requested viewport BBS can't grant |
| `STORAGE_QUOTA_EXCEEDED` | Door's KV usage exceeds quota |
| `RATE_LIMITED` | Door is sending too fast; back off |
| `SESSION_UNKNOWN` | Door addressed a session_id not currently attached |
| `INTERNAL` | Unexpected server-side error |

---

## 9. Versioning

This document is `voidcore-door-v1`. Backwards-incompatible changes bump
to `voidcore-door-v2`. Forwards-compatible changes (new optional fields,
new message types the BBS may safely ignore) stay in v1.

A door speaking v2 against a v1 BBS receives `manifest_rejected` with
`reason: "protocol_version_mismatch"`.

---

## 10. Implementation phasing

Mapped to GitHub tickets:

- **#46 — Door SDK + lifecycle.** Java in-process variant. Manifest
  parsing, registration, attach/detach, paint forwarding, reserved-key
  filtering. No KV yet.
- **#47 — First door (guess-the-number).** Pure Java, in-process,
  Normal mode. Validates the SDK end-to-end, exercises every protocol
  message that v1 supports.
- **#48 — KV storage.** `door_state` table, namespaced KV facade, CAS,
  scan. Plumb into the SDK; refactor #47 to use it for the user's
  best score.
- **#49 — Networked sidecar.** Real WebSocket transport, manifest
  registration via `hello{}`, capability negotiation, configurable
  upstream URL. First non-Java door (probably a Python tinyMUD-style
  experiment).
- **#50 (v2) — Deferred mode.** Suspend/resume, force-exit keystroke,
  full-screen ownership, redraw protocol.
- **#51+ (v3+) — DOS shim.** Sidecar wrapping DOSBox; speaks
  `voidcore-door-v1` outward; reads CP437/ANSI from a virtual COM port.

---

## 11. Open questions

These are deliberately unresolved in v1; revisit per ticket.

- **Door discovery.** v1: hardcoded list in `application.yml`. v2:
  auto-discover via mDNS or a registry endpoint?
- **Sandboxing the in-process variant.** Java security manager is
  deprecated; alternatives (modules, `java.lang.foreign` restrictions)
  are weaker. For now, in-process doors are *trusted code*. Networked
  doors are isolated by process boundary.
- **Multi-tenancy of a single door process.** A networked door
  serving 64 users uses one DB connection pool; how does the BBS
  observe its health? Probably an `ops.health{}` ping every 30s.
- **Time / scheduling.** Should doors get a "wake me at T" callback
  for daily-reset doors (LORD-style), or do they poll? Polling is
  simpler; callbacks need the BBS to keep timer state.
- **Inter-door messaging.** Out of scope for v1. v2 question: do
  we let the leaderboard door read state from the trade-sim door?
  Probably no — encapsulation > convenience.
- **Door-side telemetry.** Should the BBS aggregate per-door
  Micrometer counters (active sessions, paint rate, errors)? Yes,
  but trivial — defer to monitoring pass.

---

## 12. Glossary

- **Door.** A stateful interactive service exposing a screen-flow
  experience to BBS users.
- **Manifest.** Door's self-description: id, version, capabilities,
  storage needs, viewport requirements.
- **Viewport.** A rectangular cell region of `region-main` granted
  to the door for rendering. Door coordinates are local; BBS
  translates to user coordinates.
- **Session (in door context).** One user's attachment to one door.
  `session_id` is the key. Distinct from BBS auth session.
- **Mode.** `normal` (cooperative) or `deferred` (sovereign).
- **Scope (in KV context).** `user` / `shared` / `global` — see §6.1.
- **Reserved key.** A keystroke the BBS intercepts before forwarding
  to the door. Door cannot bind it.
