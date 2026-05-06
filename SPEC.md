# VOIDcore — Technical Specification

**Version:** 1.2
**Author:** Enzo (sysop)
**Status:** Draft for implementation
**Target:** Self-hosted on home infrastructure (FTTP + static IP, Hyper-V box)

> **Changes since v1.1:** Client architecture is now **smart-terminal with
> named regions**, not a thick client. The protocol carries layout
> definitions, region-targeted updates, and version-aware reconnect — but
> v1 ships a single fixed layout and a single region type. Forward-
> compatible protocol shape preserves headroom for v2 (see ROADMAP.md).
> Deep-link intents added. Session screen-state persisted to survive
> reconnects without losing user position. See ADR-016 in DECISIONS.md
> for the full design rationale.
>
> **Changes since v1.0:** Stack decisions formalised. Backend is Java 21
> + Spring Boot 3.3+ on virtual threads. Database is PostgreSQL 17.
> Deployment is via Docker Compose, with Caddy reverse-proxy living in a
> separate DMZ host.

---

## 1. Concept

VOIDcore is a web-based BBS. The site **is** the terminal — there is no
"normal" website wrapper, no nav bar, no marketing page. Visitors land on a
black screen with a connect sequence and a `login:` prompt. The aesthetic is
1992 dial-up bulletin board: CP437 character set, 16-colour DOS palette,
scanlines, monospace, keyboard-first navigation.

The board functions as a small private hangout for fans of ÆYER and people
adjacent to industrial/dark-electronic music. It is intentionally low-traffic,
intentionally weird, and intentionally not Discord. The gimmick *is* the
product.

### Design commitments

- **Web-only.** No installs, no apps, no Electron. A single URL, a browser, and
  a keyboard.
- **Real-time.** All multi-user state propagates over WebSockets. No polling.
  Chat, user presence, "new caller logged in" banners, and message-base
  notifications all push live.
- **Server-driven UI.** Application logic lives entirely on the server.
  The client is a smart terminal that paints what arrives, manages input
  mechanics locally, and survives flaky connections. It knows nothing
  about menus, chat, or any BBS concept — only about regions, cells,
  input modes, and reconnection.
- **No guests.** Reading anything beyond the login screen requires a registered
  handle. The application form is part of the theatre.
- **Aesthetic absolutism.** No modern UI affordances bleed in. No rounded
  buttons, no toasts, no hamburger menus. Everything is text, framed in CP437
  glyphs, in 16 colours, in a CRT-bezel container.
- **Persistent.** All state (users, posts, files, one-liners, message history,
  current screen position) lives in PostgreSQL.
- **Strongly-typed end to end.** Sealed Java interfaces for the WS protocol;
  records for every message variant; Flyway migrations for schema.
- **WS is the only channel.** After the initial HTTP load of the static
  bundle, every interaction goes through one persistent WebSocket. No
  REST, no AJAX, no per-action HTTP requests.

---

## 2. Architecture

### 2.1 Overview

```
                 ┌────────────────────────┐
                 │   DMZ Caddy (separate  │
   Internet ───► │   Ansible-managed)     │  TLS termination, Let's Encrypt
                 │   bbs.example.com     │
                 └───────────┬────────────┘
                             │ HTTP (private network)
                             ▼
            ┌────────────────────────────────────┐
            │   BBS VM (this docker-compose)     │
            │                                    │
            │   ┌────────────┐   WS / HTTP       │
            │   │  Spring    │ ◄──── browser ── │
            │   │  Boot app  │                   │
            │   └─────┬──────┘                   │
            │         │ JDBC                     │
            │         ▼                          │
            │   ┌────────────┐                   │
            │   │ Postgres   │ ◄── pgBackRest ─┐│
            │   │     17     │       sidecar    ││
            │   └────────────┘                  ││
            │                                   ││
            │   pgbackrest-repo volume ─────────┘│
            │         │                          │
            │         └─► restic sidecar ─► offsite (B2/S3)
            └────────────────────────────────────┘
```

- **Frontend:** Single static bundle served by Spring Boot from
  `src/main/resources/static/`. Plain TypeScript with a thin
  terminal-rendering module. No framework.
- **Backend:** Spring Boot 3.3+ on JDK 21+ with virtual threads enabled.
  WebSocket and HTTP on a single port (8080), bound to `127.0.0.1` of the
  BBS VM and reached only through the DMZ Caddy reverse proxy.
- **Database:** PostgreSQL 17 in a sibling container, app DB owned by a
  dedicated `voidcore_app` role. Schema migrations via Flyway from the app on
  startup. WAL archiving on for point-in-time recovery.
- **Reverse proxy:** Caddy on a separate DMZ host (managed by a separate
  Ansible repo). Terminates TLS via Let's Encrypt, forwards `/` and the WS
  upgrade to the BBS VM's port 8080.
- **Backup:** pgBackRest sidecar in the same compose stack, optional
  restic sidecar shipping the pgbackrest repo offsite.

### 2.2 Why WebSockets

The product is fundamentally multi-user real-time. Chat is the obvious case,
but the BBS feel requires:

- New caller banners appearing on every connected node ("`*** TRINITY has
  logged on, node 03 ***`")
- Live who-is-online list updating without refresh
- One-liners wall pushing new entries to anyone viewing it
- Message-base unread counts updating when someone posts
- The sysop being able to "break in" to chat or push a system message

REST polling would technically work and would even feel period-appropriate, but
it would burn battery on mobile, miss the snappy "node 04 just dropped" beat,
and complicate scaling. A single persistent WS per session is cleaner.

### 2.3 Why this stack

See `DECISIONS.md` for the long version. Short version:

- **Java + Spring Boot** for stack consistency with Aletheia and for proper
  algebraic data types via sealed interfaces + records.
- **JDK 21+ with virtual threads** so each WS connection is a thread, code
  reads linearly, no reactive ceremony.
- **Plain Spring WebSocket** (`WebSocketHandler`) over STOMP — full control
  of the on-the-wire envelope, ~50 lines for the topic dispatcher.
- **PostgreSQL** for operational maturity, real backup tooling, and
  multi-process access.
- **Flyway** for migrations. SQL files, no surprises.
- **Argon2id** for password hashing.
- **HikariCP** for the connection pool (Spring Boot default).
- **Jackson** with sealed-type polymorphic deserialisation, plus
  `jakarta.validation` for payload validation.
- **jOOQ** for the persistence layer — type-safe SQL DSL, codegen against
  the Flyway-applied schema. Bind parameters by default, raw SQL only on
  explicit opt-in. Defence-in-depth for an AI-assisted codebase. See
  ADR-005a in `DECISIONS.md`.

### 2.4 Frontend client structure

Source under `app/src/main/frontend/` (or a top-level `frontend/` directory if
you prefer separation — see `DECISIONS.md`). Built into a single bundle that
Gradle copies to `app/src/main/resources/static/` during `bootJar`.

```
frontend/
├── index.html              # The CRT shell, loads main.js
├── src/
│   ├── main.ts             # Boot: connects WS, runs the screen loop
│   ├── terminal.ts         # CP437 renderer, type-effect, scrollback
│   ├── input.ts            # Prompt, menuKey, pause, single-key capture
│   ├── theme.css           # Palette vars, scanlines, CRT bezel, fonts
│   ├── state.ts            # Local UI state: current screen, scroll, etc
│   ├── ws.ts               # WebSocket client w/ reconnect + dispatch
│   ├── screens/
│   │   ├── connect.ts      # Boot/connect sequence
│   │   ├── login.ts        # Login + new-user application
│   │   ├── menu.ts         # Main menu
│   │   ├── files.ts        # Releases + NFO viewer
│   │   ├── bulletins.ts    # Read announcements
│   │   ├── messages.ts     # Message board (list / threads / post)
│   │   ├── chat.ts         # Multinode chat room
│   │   ├── oneliners.ts    # Wall
│   │   ├── users.ts        # User list, last callers, who's online
│   │   ├── netmail.ts      # VoidMail / private messaging
│   │   ├── doors.ts        # Door game launcher (stub for v1)
│   │   └── goodbye.ts      # Logout sequence
│   └── ansi/               # Static ANSI art assets (banners)
├── package.json
├── tsconfig.json
└── build.config.ts          # esbuild or bun build config
```

### 2.5 Backend module layout

Standard Spring Boot project, Gradle Kotlin DSL.

```
app/
├── Dockerfile                         # multi-stage, provided by deploy artifact
├── settings.gradle.kts
├── build.gradle.kts
├── gradlew
├── gradle/
└── src/
    ├── main/
    │   ├── java/io/aeyer/voidcore/
    │   │   ├── VoidCoreApplication.java
    │   │   ├── config/                # Spring config (WS, security, beans)
    │   │   ├── ws/
    │   │   │   ├── BbsWebSocketHandler.java
    │   │   │   ├── SessionRegistry.java
    │   │   │   ├── protocol/          # sealed ClientMessage / ServerMessage
    │   │   │   └── handlers/          # one class per message family
    │   │   ├── auth/
    │   │   │   ├── AuthService.java
    │   │   │   ├── PasswordHasher.java
    │   │   │   ├── SessionService.java
    │   │   │   └── RateLimiter.java
    │   │   ├── domain/                # records: User, Bulletin, File, ...
    │   │   ├── repo/                  # jOOQ-backed repos
    │   │   ├── presence/              # Online users, broadcasts
    │   │   ├── chat/
    │   │   ├── messages/              # message board / threads / posts
    │   │   ├── files/
    │   │   ├── netmail/
    │   │   └── sysop/
    │   ├── frontend/                  # TypeScript source (option 1, see §2.4)
    │   └── resources/
    │       ├── application.yml
    │       ├── application-prod.yml
    │       ├── db/migration/          # V1__initial_schema.sql, etc
    │       └── static/                # populated by frontend build
    └── test/
        └── java/io/aeyer/voidcore/
```

---

## 3. Data model

PostgreSQL schema. Managed by Flyway from `src/main/resources/db/migration/`.

Conventions:
- Primary keys are `BIGINT GENERATED ALWAYS AS IDENTITY`.
- Timestamps are `TIMESTAMPTZ DEFAULT now()`.
- Handles use `CITEXT` for case-insensitive uniqueness without needing a
  separate `_lower` column.
- User preferences as `JSONB` so we don't need a migration each time we add
  a per-user toggle.

```sql
-- Extensions ----------------------------------------------------------------
-- Created by the init script in the deploy artifact. Listed here so the
-- migration is self-documenting.
-- CREATE EXTENSION IF NOT EXISTS citext;
-- CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Users ---------------------------------------------------------------------
CREATE TABLE users (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  handle          CITEXT UNIQUE NOT NULL,
  pw_hash         TEXT NOT NULL,
  location        TEXT,
  setup           TEXT,
  found_via       TEXT,
  fav_genres      TEXT,
  bio             TEXT,
  preferences     JSONB NOT NULL DEFAULT '{}'::jsonb,
  joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_call_at    TIMESTAMPTZ,
  call_count      INTEGER NOT NULL DEFAULT 0,
  post_count      INTEGER NOT NULL DEFAULT 0,
  is_sysop        BOOLEAN NOT NULL DEFAULT false,
  is_banned       BOOLEAN NOT NULL DEFAULT false,
  banned_reason   TEXT
);
CREATE INDEX idx_users_lastcall ON users(last_call_at DESC);
CREATE CONSTRAINT TRIGGER users_handle_format
  CHECK (handle ~ '^[A-Za-z0-9_\-.]{3,16}$');
-- (or as a regular CHECK constraint -- whichever Flyway prefers)

-- Sessions (server-side, opaque tokens) -------------------------------------
CREATE TABLE sessions (
  token           TEXT PRIMARY KEY,                -- 32 bytes hex
  user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ NOT NULL,
  ip              INET,
  ua              TEXT,
  -- Tracks where the user is in the BBS so reconnects (and server restarts)
  -- land them in the right area instead of bouncing them to the connect
  -- screen. Updated on every navigation. Examples:
  --   { "kind": "menu" }
  --   { "kind": "thread", "id": 42 }
  --   { "kind": "release_nfo", "release_id": 7 }
  --   { "kind": "chat" }
  current_screen  JSONB NOT NULL DEFAULT '{"kind":"menu"}'::jsonb
);
CREATE INDEX idx_sessions_user ON sessions(user_id);
CREATE INDEX idx_sessions_expires ON sessions(expires_at);

-- Login attempt log (for rate limiting) -------------------------------------
CREATE TABLE login_attempts (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ip              INET NOT NULL,
  handle          CITEXT,
  success         BOOLEAN NOT NULL,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_logattempts_ip_at ON login_attempts(ip, at);

-- Announcements (sysop-authored, read-only for users) ------------------------
CREATE TABLE bulletins (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title           TEXT NOT NULL,
  body            TEXT NOT NULL,
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  pinned          BOOLEAN NOT NULL DEFAULT false
);

-- Releases. Catalog entries are document-backed; actual audio is hosted
-- elsewhere (Bandcamp/Soundcloud) and linked.
CREATE TABLE files (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  filename        TEXT NOT NULL,                   -- e.g. "DANSECYB.ZIP"
  title           TEXT NOT NULL,
  size_bytes      BIGINT NOT NULL,
  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  uploader_id     BIGINT REFERENCES users(id),
  download_count  INTEGER NOT NULL DEFAULT 0,
  nfo_text        TEXT NOT NULL,                   -- multi-line, CP437-safe
  external_url    TEXT,                            -- bandcamp/sc/spotify
  area            TEXT NOT NULL DEFAULT 'releases'
);
CREATE INDEX idx_files_area ON files(area, uploaded_at DESC);

-- Message board ("conferences" in BBS speak) ---------------------------------
CREATE TABLE message_bases (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug            TEXT UNIQUE NOT NULL,            -- e.g. "general"
  name            TEXT NOT NULL,                   -- "General Chatter"
  description     TEXT,
  sort_order      INTEGER NOT NULL DEFAULT 0,
  is_locked       BOOLEAN NOT NULL DEFAULT false
);

-- Threads -------------------------------------------------------------------
CREATE TABLE threads (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  base_id         BIGINT NOT NULL REFERENCES message_bases(id) ON DELETE CASCADE,
  subject         TEXT NOT NULL,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_post_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  post_count      INTEGER NOT NULL DEFAULT 0,
  is_pinned       BOOLEAN NOT NULL DEFAULT false,
  is_locked       BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_threads_base_lastpost ON threads(base_id, last_post_at DESC);

-- Posts ---------------------------------------------------------------------
CREATE TABLE posts (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  thread_id       BIGINT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  body            TEXT NOT NULL,                   -- plain text + ANSI markers
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  edited_at       TIMESTAMPTZ,
  is_deleted      BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_posts_thread ON posts(thread_id, posted_at);

-- Read state per user per thread (for unread counts) ------------------------
CREATE TABLE thread_read (
  user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  thread_id       BIGINT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
  last_read_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, thread_id)
);

-- Chat history (multinode chat — single global room for v1) ----------------
CREATE TABLE chat_messages (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  body            TEXT NOT NULL,
  kind            TEXT NOT NULL DEFAULT 'msg',     -- msg | action | system
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_posted ON chat_messages(posted_at DESC);

-- One-liner wall ------------------------------------------------------------
CREATE TABLE oneliners (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  author_id       BIGINT NOT NULL REFERENCES users(id),
  body            TEXT NOT NULL CHECK (length(body) <= 70),
  posted_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_oneliners_posted ON oneliners(posted_at DESC);

-- VoidMail (private messages between users) ---------------------------------
CREATE TABLE netmail (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  from_id         BIGINT NOT NULL REFERENCES users(id),
  to_id           BIGINT NOT NULL REFERENCES users(id),
  subject         TEXT NOT NULL,
  body            TEXT NOT NULL,
  sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  read_at         TIMESTAMPTZ,
  from_deleted    BOOLEAN NOT NULL DEFAULT false,
  to_deleted      BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_netmail_to ON netmail(to_id, sent_at DESC);
CREATE INDEX idx_netmail_from ON netmail(from_id, sent_at DESC);

-- Last-callers (denormalised cache, capped via app-side housekeeping) ------
CREATE TABLE last_callers (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lastcallers_at ON last_callers(at DESC);

-- Site-wide counters --------------------------------------------------------
CREATE TABLE counters (
  key             TEXT PRIMARY KEY,
  value           BIGINT NOT NULL DEFAULT 0
);
-- Seeded by Flyway: caller_count starting at 1337

-- Sysop audit log ----------------------------------------------------------
CREATE TABLE sysop_actions (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_id        BIGINT NOT NULL REFERENCES users(id),
  action          TEXT NOT NULL,
  payload         JSONB,
  at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sysop_actions_at ON sysop_actions(at DESC);
```

### Seed data (Flyway `V2__seed.sql`)

- One sysop user — handle from `VOIDCORE_SYSOP_HANDLE` env, password is the
  Argon2id hash of `VOIDCORE_SYSOP_INITIAL_PASSWORD`. The app reads these env
  vars at boot. After first login, the sysop changes their password and
  the env var becomes irrelevant.
- 4 default message-board areas: `general`, `production`, `releases`, `meta`
- The 7 existing release files as catalog entries
- 3 starter announcements (welcome, house rules, gear)
- `counters('caller_count', 1337)`

> **Bootstrap nuance.** Flyway seeding the sysop password requires the env
> vars at migration time. Easier alternative: Flyway seeds the rows
> without the sysop user, and a Spring `ApplicationRunner` creates the
> sysop on first startup if none exists. This is what the app should do.

---

## 4. WebSocket protocol

The protocol is a **render-frame and input-event channel**, not a domain-
message channel. The server emits region-targeted updates and input mode
declarations; the client paints what arrives and dispatches input events
back. The client knows nothing about what the BBS *does* — only how to
paint regions and capture input.

### 4.1 Transport

- Single WSS connection per browser tab, terminated by the DMZ Caddy and
  forwarded as plain WS to the BBS app.
- Subprotocol: `voidcore-node-v1`.
- Messages are JSON, one per WS frame. **Human-readable JSON is a hard
  requirement** — never go binary "for performance." Debugging via
  browser devtools depends on this.
- Server sends `ping` every 30s; client must respond with `pong`. Three
  missed pings = disconnect.
- Client auto-reconnects with exponential backoff (1s, 2s, 4s, capped at
  30s).
- On WS open the client and server exchange protocol versions; mismatch
  closes the connection with a clear error.

### 4.2 Message envelope

Server-side, every message is a sealed Java type. Every variant is a
`record`. Jackson is configured with `@JsonTypeInfo(use = NAME, property =
"type")` on the sealed parent so deserialisation discriminates by the
`type` field. Switch-pattern-match in the handler enforces totality at
compile time.

Both client and server messages share an envelope:

```json
{
  "id": "msg-abc-123",
  "type": "...",
  "protocol_version": "voidcore-node-v1",
  "seq": 0,
  "mac": null,
  "payload": { ... }
}
```

- `id` — client-generated, used to correlate request/response.
- `type` — discriminator for the sealed type. Dotted namespace.
- `protocol_version` — exact match required.
- `seq` — monotonic counter per direction. **Reserved for v2 per-message
  authentication.** v1 sets it to 0 and ignores incoming values.
- `mac` — keyed message authentication code. **Reserved for v2.** v1 sets
  it to `null` and ignores incoming values.
- `payload` — type-specific body.

> **Why `seq` and `mac` are present in v1 even unused:** the protocol
> shape commits to forward-compatibility for v2 session security
> (ADR-018). Adding required fields later is a breaking change; reserving
> optional fields now is free. v1 servers ignore them, v2 servers
> enforce them.

### 4.3 Region-rendering protocol

The server addresses content to **named regions**. The client knows the
layout — for v1, a single fixed layout — and renders updates to the
named region they target.

#### v1 fixed layout

```
┌────────────────────────────────────────────────────────────┐ rows
│ banner                                                      │ 0-7
│   ANSI logo, sysop info, divider                            │
├────────────────────────────────────────────────────────────┤
│ main                                                        │ 8-21
│   the active screen content -- file list, NFO, bulletin,    │
│   chat history. scrollable with server-pushed scrollback.   │
│                                                             │
├────────────────────────────────────────────────────────────┤
│ notifications                                               │ 22
│   transient async messages: sysop break-ins, new mail,      │
│   user join/leave. auto-clears after duration_ms.           │
├────────────────────────────────────────────────────────────┤
│ status / input                                              │ 23
│   command prompt, login prompt, line input, keystroke echo  │
└────────────────────────────────────────────────────────────┘
```

Five regions: `banner`, `main`, `notifications`, `status`. (The status
and input region are unified; the cursor lives in the status region when
input is active.)

> **The protocol allows the server to declare arbitrary layouts via
> `screen.define`, but v1 servers always declare the default layout.**
> This is forward-compatibility scaffolding — see ROADMAP.md for v2's
> dynamic layout direction.

#### Server-to-client region messages

- `screen.define { id, version, layout, cacheable?, ttl_seconds? }` —
  declare the structure of the active screen. v1 always sends
  `layout: "default"` and ignores the cacheable hints.

- `region.update { region, content, cursor? }` — replace a region's
  content. `content` is an array of styled text rows; see §4.5.

- `region.append { region, content }` — append rows to a region (used
  for chat-style streams). The client manages scrollback up to a
  region-defined cap.

- `region.scrollback { region, before_seq, content }` — server provides
  older content for a region in response to a client scroll request.

- `region.clear { region }` — clear a region's content.

- `region.notify { region, content, duration_ms?, level? }` — transient
  content with auto-clear. `level` is `info | warn | alert` — purely a
  styling hint, not a security boundary.

#### Server-to-client input mode messages

- `input.prompt { mode, label?, max_length?, valid_keys?, initial? }`
  — declare what kind of input the input region should accept now.
  `mode` is one of `none | keystroke | line | password`. `valid_keys`
  is required for `keystroke` mode (e.g. `"FBMCOULDNWG"`); the client
  refuses other keys locally without sending.

- `input.cancel` — server-initiated cancellation of the current input
  prompt. Used when a sysop kicks a user mid-prompt, etc.

#### Server-to-client side-effect messages

These are the closed set of out-of-band actions the server can ask the
client to perform. **No generic `client_cmd` envelope** — each is a
typed message so the protocol surface stays explicit.

- `effect.open_url { url }` — open URL in a new tab. Used by the file
  area to launch Bandcamp/SoundCloud/Spotify links.
- `effect.play_sound { name }` — play a named sound (modem, beep,
  fanfare). Client checks user preference; mute is honored.
- `effect.set_title { title }` — set the browser tab title. Used for
  unread indicators ("VOIDcore (3)").
- `effect.copy_clipboard { text }` — copy text to the system clipboard.

#### Server-to-client connection messages

- `auth.ok { user, intent_resolved? }` — authentication succeeded.
- `auth.err { code, message, field? }` — authentication failed.
- `resume.ok { sync: true }` — resume succeeded, region versions match
  client's; client keeps its painted state.
- `resume.ok { sync: false, frames: [...] }` — resume succeeded but
  state changed; included frames bring client up to date.
- `resume.err { code, message }` — token invalid or expired; client
  must re-authenticate.
- `error { code, message, ref_id? }` — generic protocol error.

#### Client-to-server messages

The client sends only a small set of message types. None of them carry
application semantics — they're terminal-mechanic events.

- `auth.login { handle, password }`
- `auth.register { handle, password, location?, setup?, found_via?,
  fav_genres? }`
- `auth.resume { token, intent?, region_versions? }` — `intent` is the
  optional deep-link target (see §4.6); `region_versions` is the
  version map the client last saw, for sync detection.
- `auth.logout`
- `keystroke { key }` — a single key was pressed in `keystroke` input
  mode. Only sent if `valid_keys` matched; invalid keys are dropped
  client-side.
- `line.submit { text }` — line input completed (Enter pressed).
- `line.cancel` — line input cancelled (Esc pressed).
- `scroll.request { region, direction, amount }` — user scrolled
  past the buffered range, request older content.
- `viewport.resize { cols, rows }` — viewport size changed (browser
  resize, mobile rotation). Server may respond with re-rendered
  regions sized for the new viewport.

That's the entire client-to-server vocabulary. **Eight message types.**
The reduction from v1.1's ~30 message types is the architecture
working: with application logic on the server, the client only needs
to report mechanical events.

### 4.4 Cell content format

The `content` field of a region update is an array of rows. Each row is
an array of styled spans:

```json
{
  "region": "main",
  "content": [
    {
      "row": 0,
      "spans": [
        { "text": "  ", "fg": "default", "bg": "default" },
        { "text": "[F]", "fg": "br_yellow", "bg": "default", "bold": true },
        { "text": " Releases", "fg": "bright", "bg": "default" }
      ]
    },
    { "row": 1, "spans": [...] }
  ]
}
```

Colours are named (`black`, `red`, `green`, `yellow`, `blue`, `magenta`,
`cyan`, `white`, `bright_*` for the high-intensity variants, plus
`default`). Row indexes are within the region, not the screen — the
region's offset is determined by the layout. Rows omitted from a
`region.update` are cleared; rows present are painted.

### 4.5 Reconnect and version reconciliation

Each region has an integer version number, incremented on every update.
The server caches the per-connection "last sent version" for each region
in memory (lost only on full server restart). The client retains the
last-painted content of each region and the version it represents.

On `auth.resume`, the client includes `region_versions: { banner: 17,
main: 42, notifications: null, status: 3 }`. The server compares to its
cache:

- **All versions match:** reply `resume.ok { sync: true }`. No content
  sent. Client keeps its painted state. This is the common case for
  flaky-connection reconnects on mobile.
- **Some versions differ:** reply `resume.ok { sync: false, frames: [...] }`
  with `region.update` frames for the changed regions only.
- **Server cache is empty (server restart):** reply `resume.ok { sync:
  false, frames: [...] }` with full frames for all regions. Client
  loses any in-progress input but retains session token.

The client never auto-clears regions on reconnect — only on explicit
`region.clear` or `region.update`. This means a flaky connection on a
train shows "reconnecting..." in the status line while the user's
screen content persists, and resumes invisibly when sync succeeds.

### 4.6 Deep-link intents

The URL fragment carries an optional destination hint that survives the
connect sequence and login flow:

```
https://bbs.example.com/#nfo/danse-cybernetica
```

On page load, the client reads the fragment. After successful auth, the
client sends `auth.resume { token, intent: "nfo/danse-cybernetica" }`
(or includes `intent` in the original `auth.login`).

The server's screen state machine checks for a pending intent after
auth. If valid, it navigates to that screen instead of the main menu.
If invalid (typo, missing resource, no permission), it lands on the
menu and emits a `region.notify` with `*** intent "foo" not recognised
***`.

#### v1 intent grammar

Closed set:

| Intent | Destination |
|--------|-------------|
| `nfo/<filename>` | Releases, specific NFO open |
| `bulletin/<id>` | Specific bulletin |
| `chat` | Drop into chat |
| `user/<handle>` | Documents faceted view filtered to that user's authored docs (per ADR-023 / SPEC-documents §4 — "the user IS the set of their authored docs"; no separate profile-screen primitive) |
| `doc/<slug>` | Specific document (PR-3) |
| `thread/<id>` | Specific message thread (Phase 3) |

Adding new intents is a server-only change.

### 4.7 Error codes

| Code | Meaning |
|------|---------|
| `AUTH_REQUIRED` | Hit a privileged action without a session |
| `INVALID_CREDENTIALS` | Bad handle/password |
| `RATE_LIMITED` | Too many attempts, includes `retry_after_ms` |
| `HANDLE_TAKEN` | Registration: handle exists |
| `HANDLE_INVALID` | Registration: bad characters/length |
| `BANNED` | User is banned, includes `reason` |
| `INTENT_INVALID` | Deep-link intent unrecognised or unauthorised |
| `PROTOCOL_VERSION_MISMATCH` | Client/server protocol versions don't match |
| `VALIDATION` | Generic validation failure, includes `field` |
| `INTERNAL` | Server-side error; logged with a `ref_id` for the sysop |

---

## 5. Authentication & sessions

- Passwords hashed with **Argon2id** via `de.mkammerer:argon2-jvm`.
  Parameters from env vars (`VOIDCORE_ARGON2_*`), defaults `memory=64MB,
  iterations=3, parallelism=4`. Tune to ~250ms per hash on the deploy box.
- Sessions are random 32-byte tokens (hex-encoded), stored in the
  `sessions` table. Client stores in `localStorage` under `voidcore:session`.
- Session TTL: 30 days sliding (configurable via
  `VOIDCORE_SESSION_TTL_DAYS`). Each `auth.resume` updates `last_seen_at` and
  extends `expires_at`.
- On `auth.login.ok`, server returns the token; client persists it.
  Subsequent reconnects send `auth.resume { token }` first.
- **Rate limiting** (in-process; a Caffeine-backed `RateLimiter` bean):
  - Login: 5 failed attempts per IP per 15 min → 15 min lockout.
  - Registration: 3 per IP per hour.
  - One-liner / chat: 10 messages per minute per user, 1 per second burst.
- Handle rules: `^[A-Za-z0-9_\-.]{3,16}$`, case-insensitive uniqueness via
  the `CITEXT` column type.
- Password rules: minimum 8 chars. No upper limit beyond Argon2's input
  size cap.
- IP detection: the app trusts `X-Forwarded-For` only when
  `VOIDCORE_TRUST_PROXY=true` (set in the compose stack). Behind the DMZ
  Caddy this is the source of truth.

---

## 6. Smart-terminal client

The client is a **smart terminal**, not a thick client. It has zero
knowledge of BBS application logic — no concept of menus, chat, files,
threads, or any other BBS-specific entity. It implements:

- A region renderer
- An input mode state machine
- Connection management with version-aware reconnect
- A small set of side-effect handlers
- The CRT shell aesthetic (CSS only)

Everything else lives on the server.

### 6.1 Bundle layout

Source under `app/src/main/frontend/`. Built by Gradle as part of
`bootJar` (esbuild or Bun). Output bundle goes to
`app/src/main/resources/static/` and is served by Spring Boot.

```
frontend/
├── index.html              # CRT shell, loads main.js
├── src/
│   ├── main.ts             # Boot: open WS, run dispatch loop
│   ├── ws.ts               # WebSocket client, reconnect, version-aware resume
│   ├── envelope.ts         # JSON envelope encode/decode + protocol version check
│   ├── layout.ts           # Region geometry, viewport sizing
│   ├── region.ts           # Per-region renderer + scrollback buffer
│   ├── input.ts            # Input mode state machine, line buffer, local echo
│   ├── effects.ts          # open_url, play_sound, set_title, copy_clipboard
│   ├── theme.css           # CRT shell, scanlines, flicker, noise, palette
│   └── intent.ts           # Read URL fragment, send with auth
├── package.json
├── tsconfig.json
└── build.config.ts
```

Estimated size: 1500-2000 lines total. The bundle is a runtime, not a
collection of screens.

### 6.2 The dispatch loop

`main.ts` is genuinely tiny. It opens the WS, reads incoming messages,
dispatches each one to its handler. It contains no application logic
because there is no application logic on the client.

```ts
async function main() {
  await initLayout();              // build the default layout regions
  const intent = readUrlFragment();
  const ws = await openWebSocket();

  ws.onMessage = (msg) => {
    switch (msg.type) {
      case "screen.define":  layout.define(msg.payload); break;
      case "region.update":  region.update(msg.payload); break;
      case "region.append":  region.append(msg.payload); break;
      case "region.scrollback": region.scrollback(msg.payload); break;
      case "region.clear":   region.clear(msg.payload); break;
      case "region.notify":  region.notify(msg.payload); break;
      case "input.prompt":   input.setMode(msg.payload); break;
      case "input.cancel":   input.cancel(); break;
      case "effect.open_url":      effects.openUrl(msg.payload); break;
      case "effect.play_sound":    effects.playSound(msg.payload); break;
      case "effect.set_title":     effects.setTitle(msg.payload); break;
      case "effect.copy_clipboard": effects.copyClipboard(msg.payload); break;
      case "auth.ok":        handleAuthOk(msg.payload); break;
      case "auth.err":       handleAuthErr(msg.payload); break;
      case "resume.ok":      handleResumeOk(msg.payload); break;
      case "resume.err":     handleResumeErr(msg.payload); break;
      case "error":          showError(msg.payload); break;
    }
  };

  // Try resume first if we have a token
  const token = localStorage.getItem("voidcore:session");
  if (token) {
    ws.send({ type: "auth.resume", payload: { token, intent,
              region_versions: region.getAllVersions() } });
  }
  // Otherwise the server will eventually push the connect screen
  // and prompt for login.
}
```

That's the shape. Maybe 50 lines including error handling. The rest of
the client is the modules invoked above.

### 6.3 Region renderer

Each region is a DOM element with a fixed geometry derived from the
layout. The renderer:

- Maintains a per-region content buffer (rows of styled spans)
- Maintains a per-region version number (received from server)
- Supports `update`, `append`, `scrollback`, `clear`, `notify`
- Handles transient notifications with auto-clear timers
- Supports local scrolling within the buffered range; emits
  `scroll.request` when user scrolls past the edge
- Repaints on viewport resize

The buffer is just a 2D structure of `{ text, fg, bg, bold }` cells.
Rendering is innerHTML or a per-cell DOM update — at 80x24 with infrequent
updates, the simpler approach is fast enough.

### 6.4 Input mode state machine

The input region operates in one of four modes:

- **`none`**: no input accepted. The cursor is hidden.
- **`keystroke`**: single keypress. The client filters by `valid_keys`,
  drops invalid keys silently (or with a soft beep), sends valid ones
  immediately as `keystroke { key }`. **No echo** — the server's
  response decides what appears next, typically a screen transition
  that paints the result.
- **`line`**: full line input. The client maintains a local buffer,
  echoes characters in the input region as typed, supports backspace
  and basic line editing. On Enter, sends `line.submit { text }`. On
  Esc, sends `line.cancel`. **Echo is local** — a 200ms RTT user types
  at native speed.
- **`password`**: same as `line` but echoed as `*`. Buffer is cleared
  from memory after submit (no localStorage, no JS-accessible
  retention).

The state machine is small. Total LOC for `input.ts`: ~200 lines.

### 6.5 The status row and keystroke acknowledgement

When the input is in `keystroke` mode and the user presses a valid key,
the client immediately paints the keypress in a designated area of the
status row (e.g. `> F`). This gives instant visual feedback even though
the screen transition takes 200ms RTT. When the server's response
arrives (typically a `region.update` for the main region), the status
row is replaced as part of the new frame.

This is zero-protocol-overhead latency masking. The client doesn't know
what F means; it just shows that F was pressed while waiting for the
response. The server's frame replaces the echo in due course.

### 6.6 Connection management and reconnect

The WS client maintains:

- A reconnect schedule with exponential backoff (1s, 2s, 4s, 8s, 16s,
  cap 30s)
- A "connection state" indicator that paints in the status row:
  `ONLINE` (default, invisible), `RECONNECTING...` (flashing),
  `OFFLINE` (after extended failure)
- The current region version map, so reconnect can request sync

On disconnect, the client **does not clear regions**. Painted content
stays visible. The status row indicator changes to "RECONNECTING...".

On reconnect:

1. WS opens.
2. Client sends `auth.resume { token, intent, region_versions }`.
3. Server replies `resume.ok { sync: true }` (no content sent) or
   `resume.ok { sync: false, frames: [...] }`.
4. Sync case: status row clears, user sees "ONLINE" briefly, returns to
   their work as if nothing happened.
5. Non-sync case: client paints the included frames; in-progress line
   input is preserved if still in line mode (the input region wasn't
   in the changed-frames list); otherwise it's lost.

For mobile users on flaky connections (the train scenario), the
sync-true case is the common path — most reconnects involve no content
change because no time passed in user-perceived terms.

### 6.7 Mobile

- Detect narrow viewport (<700px). Reduce font to 16px. The default
  layout's columns shrink from 80 to 64 where possible (the server is
  informed via `viewport.resize` and re-renders content sized for the
  new viewport).
- Auto-focus a hidden input element to summon the soft keyboard when
  any input mode is active.
- Single-key menus: render a thin row of large tap targets above the
  prompt on mobile, hidden on desktop. Tapping a target sends the same
  `keystroke` message a real keypress would.
- Accept that BBS-on-phone is a compromise; document it but don't
  degrade the desktop experience to suit it.

### 6.8 Accessibility

- The CRT shell has `aria-hidden="true"` on decorative effects (scanlines,
  vignette).
- Each region has a semantic role: `banner` is `role="banner"`, `main`
  is `role="main"`, `notifications` is `role="status" aria-live="polite"`,
  `status` is `role="contentinfo"`.
- Colour is never the only carrier of meaning (unread items get glyphs,
  not just brighter colour).
- Respect `prefers-reduced-motion`: disable the connect-sequence
  typewriter effect and CRT flicker when set.

### 6.9 Theming

CSS variables for the full 16-colour palette. The user can toggle
between named themes (phosphor / amber / CGA / modern). Theme is sent
to the server via a sysop-screen preference and stored in
`users.preferences->'theme'`. The client reads it on connect and
applies the matching CSS variable set.

### 6.10 Aesthetic source of truth

The v0 demo HTML in `reference/v0-artifact-demo.html` is the visual
source of truth for v1. The smart-terminal client renders the same
cells in the same colours, the same banner art, the same NFO formatting.
Lift the CSS verbatim. See `reference/README.md`.

### 6.11 The contract: server decides, client renders

A single design tenet underwrites everything in §6. Naming it so future
PRs can point at it without re-litigating each "wouldn't it be easier
if the client just…" suggestion:

> **The client renders. It does not decide.**

What each side owns:

| Layer | Owns | Does not own |
|---|---|---|
| **Server** | Application logic, state, transitions, persistence, all routing decisions, per-screen layout (regions, frames, prompts, modes) | Pixel positioning, font metrics, browser resize behaviour, animations, the CRT aesthetic shell |
| **Client** | Rendering competence — frames, named regions, layout (V2 onwards), themes, prompt input modes, scroll offsets, side-effect handlers (open URL, set title, set theme) | Application state, business rules, navigation routing, "what happens when I press B," any decision about *what* to display next |

This sits deliberately between two more common positions:

- **Thinner than a webapp.** Most browser apps in 2026 are thick clients
  with state, routing, and business logic on the client; the server is a
  JSON API. SYSOP:NODE inverts that — the server is authoritative and
  imperative, the client is reactive and dumb-in-a-good-way.
- **Thicker than a terminal.** Classic BBSes emit ANSI byte streams; the
  client (a terminal emulator) renders text and cursor moves with no
  semantic understanding at all. SYSOP:NODE's client knows about typed
  protocol messages, named regions, frame sequence numbers, themes, and
  prompt modes — but stops there.

**Architectural lineage:** this is the X11 / RDP / VNC split — display
server / display client — done over WebSocket with a structured frame
format instead of pixel ops or ANSI bytes.

**Three concrete payoffs:**

1. **Resilience.** A JVM restart mid-session lands the user back on the
   same screen via `current_screen` lookup (§13). This works because the
   client carries no application state. There is nothing to desync.
   Webapps cannot do this cleanly because the client has accumulated form
   fields, optimistic updates, and routing state that diverges from the
   server.

2. **Client portability.** The protocol contract is at the right level
   for *any* renderer. A native macOS/iOS client, a TUI client written in
   Go, an `nvim` plugin, a Node-based terminal client, an
   accessibility-focused screen-reader client — all of them consume the
   same protocol and require zero re-implementation of BBS logic. None
   of them inherit a single switch statement of "what happens when…"
   from the server. Compare to webapps where shipping a native client
   usually means rewriting half the application.

3. **Debuggability.** When something behaves wrong, "is this a server
   bug or a client bug?" has an actual answer. The boundary is sharp.
   In webapps the same logic frequently exists in both places, and
   bugs straddle the line.

**The test of leakage:** *"Could a TUI client written in Go (or `nvim`,
or Node, or a SwiftUI app) implement the protocol from scratch and have
the BBS work correctly without re-implementing any application logic?"*
If the answer is no, the abstraction is leaking. Every protocol change
should be evaluated against this test before merging.

This is why region positioning moves into the protocol (per ADR-031,
ADR-032), why themes are CSS-only on the client side, why deep-link
intents are server-resolved per SPEC §4.6, why screens own their
rendering (per ADR-025) — each of those choices keeps the contract on
the right side of the line.

---

## 7. Sub-systems detail

### 7.1 Releases

Catalog of releases. Listing shows filename, size, date, download count,
title. Drilling in shows a full NFO in CP437 box-drawing, plus an external
link button (opens in new tab to Bandcamp/SoundCloud/Spotify). Click on the
download link increments the counter server-side and broadcasts a
`files.download_clicked` event so the listing updates live for everyone.

NFO authoring is sysop-only, via a sysop screen accessed from the `[S]`
menu entry. No CLI tooling required.

Areas (`area` column) allow grouping releases vs sets vs unreleased
material. v1 ships with `releases` only.

### 7.2 Message board

Threaded forum, IPB-style. Each base has a list of threads sorted by
`last_post_at desc`, with pinned threads on top. Threads show subject,
author handle, post count, last poster, last-post age. Drilling in shows
posts oldest-first with author handle, posted-at, body. Replying creates a
new post in the thread; starting a new thread asks for subject + body.

Unread counts are computed per user via the `thread_read` table. A thread
is unread if `threads.last_post_at > thread_read.last_read_at` (or no
`thread_read` row exists). The menu shows unread counts inline:
`[M] Message board  (12 new)`.

Post bodies are plain text with a small set of inline ANSI markers:
`{c:text}` to colour a span (`c` = single CP437 colour code). No HTML, no
markdown. Mention syntax `@handle` renders as bright magenta and (if the
mentioned user is online) sends them a soft system notify.

Editing own posts allowed within 10 min. After that, edits require sysop.

### 7.3 Multinode chat

Single global room in v1. Optional named rooms in a later phase if usage
warrants.

Chat history persists. On entering the chat screen, the last 50 messages
load. Older messages page in via `chat.history { before }` as the user
scrolls up (or hits `[<]` for older).

`/me` and `/action` produce italic-action lines (`* SYSOP nods`).
`/who` lists everyone currently in chat (a subset of online users).
`/clear` clears local scrollback only.
`/quit` exits to main menu.

Presence: entering chat broadcasts `chat.presence { handle, joined: true }`.
Typing indicator is debounced to 500ms — server collects active typers
across all connections and emits `chat.typing.update` at most once per
second.

### 7.4 One-liners wall

Bottom-of-screen CGA-banner-style scroll of recent quips. Adding one
broadcasts `oneliners.new` so the wall updates live for everyone viewing
it. Capped at most-recent 100 stored, top 30 shown by default.

Text-only, ≤70 chars. No mentions, no formatting.

### 7.5 VoidMail

Inbox / outbox / compose. Subjects ≤64 chars, bodies ≤4KB.
Recipient must be a registered handle. On send, if the recipient is online
they get a `*** New mail from HANDLE ***` banner pushed via the WS.

Deleting from inbox sets `to_deleted=true`; from outbox sets
`from_deleted=true`. Rows where both are true get pruned weekly via a
scheduled `@Scheduled` task.

### 7.6 Doors

Stub for v1 (menu entry exists, opens to "queueing for compile" message).

Future architecture: each door is a self-contained class implementing:

```java
public interface DoorGame {
  String slug();
  String name();
  String description();
  void enter(Session session, Terminal term, WsChannel ws);
}
```

Doors run in the same JVM and stream their UI through the WS using the
same terminal protocol the rest of the BBS uses. State persists in
dedicated tables per game.

First door target: a tiny LORD-alike themed around running an underground
record label. Daily turns, sign artists, drop releases, beef with rivals.
Don't build until the message board and chat are solid.

### 7.7 Sysop tools

A user with `is_sysop=true` has a `[S]` menu entry that exposes:

- User management (view, edit, ban, unban)
- Bulletin compose
- File catalog edit
- Broadcast message ("break in")
- Live log of all WS connections
- DB stats

Sysop actions log to the `sysop_actions` table.

---

## 8. Real-time event flows

### 8.1 New caller logs in

```
1. Client sends auth.login or auth.resume on a fresh WS connection.
2. Server validates, creates/updates session.
3. Server adds connection to presence registry under node N (lowest free).
4. Server broadcasts system.user_joined { handle, node } to all other
   connections.
5. Server broadcasts presence.online with the updated node list.
6. Client, on receiving system.user_joined while on the main menu or chat,
   prints:
     *** TRINITY has logged on, node 03 ***
   in bright cyan.
```

### 8.2 New chat message

```
1. Client sends chat.send { body }.
2. Server validates length, rate limit, persists.
3. Server broadcasts chat.message { message } to all connections currently
   subscribed to chat.
4. Sender's client also receives the broadcast and renders it (instead of
   optimistically rendering — keeps ordering consistent with other clients).
```

### 8.3 Sysop break-in

```
1. Sysop client sends sysop.broadcast { body }.
2. Server checks is_sysop, persists in chat_messages with kind='system',
   broadcasts system.announce { body } to every connection regardless of
   screen.
3. Every client renders, full-width:
     ╔══════════════════════════════════════════════════════════════╗
     ║ *** SYSOP: server going down for upgrade in 5 minutes ***    ║
     ╚══════════════════════════════════════════════════════════════╝
   in bright red, then a small fanfare beep (web audio, opt-in).
4. Action logged in sysop_actions.
```

---

## 9. Operations

### 9.1 Configuration

All configuration is via env vars consumed by Spring Boot. The deploy
artifact's `.env.example` is the canonical list. Spring binds them under
the `voidcore.*` prefix via `@ConfigurationProperties`.

```
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/voidcore
SPRING_DATASOURCE_USERNAME=voidcore_app
SPRING_DATASOURCE_PASSWORD=...

VOIDCORE_PUBLIC_URL=https://bbs.example.com
VOIDCORE_SYSOP_HANDLE=sysop
VOIDCORE_SYSOP_INITIAL_PASSWORD=...
VOIDCORE_SESSION_TTL_DAYS=30
VOIDCORE_TRUST_PROXY=true
VOIDCORE_ARGON2_MEMORY_KB=65536
VOIDCORE_ARGON2_ITERATIONS=3
VOIDCORE_ARGON2_PARALLELISM=4

JAVA_OPTS=-XX:+UseZGC -XX:MaxRAMPercentage=60 -Djava.net.preferIPv4Stack=true
```

### 9.2 Deployment

Target: the Hyper-V box on home FTTP with static IP, via the Docker
Compose stack in the deploy artifact.

- BBS VM runs the compose stack: postgres + app + pgbackrest (+ optional
  restic).
- DMZ Caddy on a separate host (managed by a separate Ansible repo)
  terminates TLS via Let's Encrypt and reverse-proxies to the BBS VM.
- Process supervision: Docker's `restart: unless-stopped` on each service.
  Docker daemon under systemd.
- Database: PostgreSQL 17 in container, data on a Docker volume. Schema
  migrations run by Flyway on app startup.
- Logs: stdout to Docker's json-file driver, rotated 10MB × 3 per
  service. Aggregate to journald or Loki if desired (out of scope for v1).
- Updates: `make build && docker compose up -d app` for app changes.
  Postgres minor version upgrades are in-place; major upgrades require
  pg_upgrade.

### 9.3 Monitoring

- `/actuator/health` endpoint (Spring Boot Actuator) returns DB
  connectivity + WS connection count via a custom HealthIndicator.
- `/actuator/metrics` exposes Micrometer metrics. No Prometheus scrape in
  v1 — add later if usage warrants.
- `make ps` shows compose service health.

### 9.4 Backup

Three-layer strategy, fully covered in the deploy artifact's
`docs/BACKUP.md`:

1. **pgBackRest** sidecar — weekly fulls + daily incrementals + WAL
   archive. Local volume.
2. **Restic** sidecar (optional) — ships pgbackrest repo offsite to
   B2/S3/SFTP, encrypted, deduplicated, with retention.
3. **Logical pg_dump** — optional, documented but not in compose.

Recovery is documented in `docs/RESTORE-RUNBOOK.md`. Three scenarios:
PITR on live, scratch-restore for verification, full disaster recovery
from offsite. Quarterly verification checklist included.

### 9.5 Abuse handling

- Sysop can ban users; banned users see a banner and disconnect on next
  action.
- IP-level bans handled at the DMZ Caddy via include file the sysop tool
  writes (out of scope for v1; add when needed).
- All chat and post bodies stored as-typed (not modified). Bad content
  removed by setting `is_deleted=true`; original text retained for audit.

---

## 10. Build phases

### Phase 1 — Foundation (must ship together)

- Spring Boot project skeleton, Gradle Kotlin DSL, JDK 21+
- Flyway migrations: V1 schema, V2 seed bases/files/announcements, V3 counters
- WS server (Spring `WebSocketHandler`), message envelope, ping/pong
- Sealed `ClientMessage` / `ServerMessage` types; Jackson polymorphic
  deserialiser; `jakarta.validation` on payloads
- Client reconnect with exponential backoff
- Terminal module + CRT shell on client
- Connect sequence, login screen, new-user application
- Auth (Argon2id, sessions, rate limit)
- Sysop bootstrap on first run (creates sysop user from env if none exists)
- Main menu
- Announcements (read-only) — sysop seeds via sysop screen
- Releases + NFO viewer — sysop seeds via sysop screen
- User list, last callers, who's online (live)
- Goodbye / logout

This is enough to be a complete coherent v1 — same surface area as the
original artifact but with a real backend, real DB, and live presence.

### Phase 2 — Social

- One-liners wall (live updates)
- Multinode chat (single room, history, typing indicator)
- VoidMail
- System banners on user join/leave
- Mention notifications

### Phase 3 — Forum

- Message board CRUD
- Threads, posts, edit window
- Unread tracking
- Live "new post" notifications

### Phase 4 — Sysop & polish

- Sysop screens (user mgmt, announcements compose, release catalog edit, broadcast)
- Theme switcher (phosphor / amber / CGA / modern)
- Mobile UX pass
- Accessibility audit
- Backup verification automation (quarterly reminder + scratch-restore
  script)
- Monitoring dashboards

### Phase 5 — Doors

- Door framework
- First door: label-sim LORD-alike

---

## 11. Non-goals (for now)

- **Federation.** Not joining FidoNet, not bridging to Matrix. The
  `1:23/45` address is flavour. If federation ever happens it's its own
  spec.
- **Mobile-first design.** Mobile works, desktop is the canonical
  experience.
- **Multiple chat rooms.** Implemented in Phase A with named rooms and room-scoped history.
- **Voice/video.** No.
- **File uploads.** v1 releases are sysop-curated. User uploads are a
  separate spec with its own moderation requirements.
- **Search.** Browsing is the point. If history grows large enough that
  search matters, add Postgres `tsvector` + GIN indexes over `posts.body`
  and `chat_messages.body`.
- **Mobile push notifications.** Web push is possible but the BBS metaphor
  fights it. If you're not connected, you're not connected.
- **Multi-node clustering.** Single instance is plenty for board scale.
  If horizontal scale is ever needed, presence registry moves to Redis
  and broadcasts go through Postgres LISTEN/NOTIFY.

### v1 / v2 boundary

This spec is for **v1**: a private BBS as a feature of the ÆYER
website. Scope is bounded by the design commitments in §1 and the
acceptance criteria in §13. v1 is finished when those criteria pass.

**v2 is a major uplift, not a feature addition.** It reframes the
project from "ÆYER's BBS" into a terminal-aesthetic application
runtime that could be packaged as an external product. v2's defining
features:

- **Dynamic UI runtime.** The server emits layout definitions
  describing arbitrary screen structures; the client renders whatever
  shape arrives. Multiple region types (lists, forms, progress bars,
  marquees) beyond the v1 text-cell region.
- **Cacheable screens and content.** Screens declare cacheability and
  TTL; clients cache layouts and static content; pre-fetching
  speculatively loads likely-next screens.
- **Forward-secure session security.** Mutual key exchange (X25519) at
  session establishment, per-message HMAC authentication, debugging
  tool that verifies MACs given a session secret instead of disabling
  the security layer.
- **Door-game framework** based on the dynamic runtime: doors define
  their own layouts, render arbitrary screens, and become first-class
  rather than bolted-on.

v2's full scope is captured in `ROADMAP.md`. The relevant ADRs in
`DECISIONS.md` (ADR-016 through ADR-018) document the v1/v2 split as
an explicit architectural decision, not a maybe.

**v1 reserves protocol surface for v2 to ensure forward-compatibility
without breaking changes.** Specifically: the `seq` and `mac` envelope
fields, the `screen.define` message type, and the layout grammar are
all present in v1 but only minimally exercised. v1 servers must accept
and ignore these fields; v2 servers will require them. See ADR-017 and
ADR-018.

---

## 12. Open questions

1. **Display name vs handle.** Allow a separate display name (Unicode,
   spaces) shown alongside the ASCII handle? Adds flexibility, dilutes
   handle culture. Default: no.
2. **Public URL for unauthenticated visitors.** Hitting the URL without a
   session shows the full connect sequence and login prompt. Confirmed.
3. **Anonymous posting.** Doors / minigames could allow anon scoreboards.
   No-op for v1.
4. **Encryption at rest.** Postgres data is unencrypted on disk.
   Acceptable for the data sensitivity (no real PII). If sensitive PII is
   ever stored, revisit with disk-level encryption (LUKS on the volume).
5. **Federation address.** `23:495/0` is currently arbitrary. Is there a
   more meaningful number (release year, BPM, etc.) worth using?

---

## 13. Acceptance criteria for v1 (Phase 1)

**Functional:**

- New visitor hits the URL on a fresh browser, sees connect sequence, lands
  on `login:` prompt within 2 seconds of page paint.
- Visitor can register a handle, log in, and reach the main menu without
  seeing any modern web UI affordance (no buttons, no dialog modals, no
  toasts, no navigation chrome).
- After registering, the visitor's handle appears in the user list and
  last-callers immediately, and on a second concurrent browser tab the
  user list updates live without refresh.
- Releases displays all 7 seed releases. Selecting one shows a properly
  bordered NFO. Clicking the external link increments the download
  counter live for both tabs.
- Announcements display all 3 seed announcements with correct formatting.
- Logout shows the NO CARRIER sequence and disconnects the WS cleanly.
- Reconnecting within session TTL skips the password and resumes via token.
- A second concurrent visitor sees `*** HANDLE has logged on, node 02 ***`
  in their `notifications` region when the first user logs in.

**Smart-terminal architecture:**

- The client bundle is < 200KB minified+gzipped.
- The client source contains zero references to BBS application concepts
  (no `chat.ts`, no `files.ts`, no `menu.ts`). It implements regions,
  input modes, reconnect, and effects only.
- Hitting `https://bbs.example.com/#nfo/danse-cybernetica` lands the
  user (after login) directly on the Danse Cybernetica NFO instead of
  the main menu.
- An invalid intent (`/#nfo/does-not-exist`) shows the main menu plus a
  `*** intent not recognised ***` notification.

**Reconnect resilience:**

- Disconnecting the WS (browser DevTools "Offline" mode) shows
  `RECONNECTING...` in the status row but does not clear any region
  content.
- Reconnecting after 30 seconds (no server-side state change during the
  gap) returns the user to the same screen with no visible disruption.
  The status row clears.
- Reconnecting after a chat message arrived during the gap renders the
  new message via a `region.append` on `main` (if user was in chat) or
  updates the unread-count indicator (if elsewhere).
- Restarting the JVM mid-session results in users being re-rendered to
  the same screen via `current_screen` lookup, with no requirement to
  re-authenticate or navigate manually.
- Mobile users on a flaky train-tunnel connection (simulated with 30s
  drop/recover cycles) experience no loss of in-progress line input
  across at least 5 consecutive drop/recover cycles.

**Operational:**

- All of the above works on Chrome, Firefox, and Safari (desktop) and
  Chrome and Safari (mobile).
- WSS endpoint passes Mozilla Observatory + ssllabs A or A+ (validated at
  the DMZ Caddy).
- `make backup-info` shows at least one full backup.
- Argon2 verify on the deployment box takes 200–400ms per attempt.
- A scratch-restore from pgBackRest into a separate container produces a
  DB whose row counts match the live DB (per RESTORE-RUNBOOK §B).
- Protocol version mismatch between client and server produces a clear
  error message rather than a silent failure or cryptic crash.

---

## 14. Tech-stack reference

- **Runtime:** OpenJDK 21+ (Eclipse Temurin), virtual threads enabled.
- **Framework:** Spring Boot 3.3+ with Spring Web + Spring WebSocket +
  Spring Boot Actuator.
- **Build:** Gradle (Kotlin DSL), Spring Boot's layered JAR support.
- **Database:** PostgreSQL 17 (`citext`, `pgcrypto`, `jsonb`).
- **JDBC:** HikariCP (default), `org.postgresql:postgresql` driver.
- **Persistence:** jOOQ Open Source Edition. Codegen runs in the Gradle
  build using Testcontainers (throwaway Postgres applies Flyway
  migrations, jOOQ inspects the live schema). Domain objects are Java
  records; repos use `DSLContext` internally. Raw SQL only via explicit
  `DSL.sql(...)` opt-in.
- **Migrations:** Flyway, SQL files only.
- **Validation:** `jakarta.validation` (Hibernate Validator).
- **Password hash:** `de.mkammerer:argon2-jvm`.
- **Rate limit:** Caffeine cache, custom `RateLimiter` bean.
- **JSON:** Jackson, polymorphic via sealed types and `@JsonTypeInfo`.
- **Logging:** SLF4J + Logback, JSON layout to stdout.
- **Testing:** JUnit 5, AssertJ, Testcontainers (postgres), Spring
  WebSocket test support for end-to-end WS tests.
- **Frontend:** TypeScript 5+, esbuild or Bun for bundling, no framework.
  Bundle copied to `src/main/resources/static/` during Gradle build.

---

## 15. Risks

1. **Sole-operator burnout.** Backups, security patches, abuse handling
   all fall on the sysop. Mitigated by tight scope, automated backups,
   and willingness to put the board in read-only mode if needed.
2. **Spam / abuse.** Even a small board attracts bots. Rate limiting and
   the registration form's friction help. Captcha is anti-vibe; avoid
   unless forced.
3. **Scope creep.** Phases above are the contract; new ideas go in a
   backlog.
4. **WebSocket through corporate proxies.** Some users behind aggressive
   proxies will lose WS. Reconnect logic + a "connection dropped" banner
   is the only mitigation.
5. **Mobile keyboard quirks.** iOS especially. Acceptance criteria
   includes mobile, so test early.
6. **Restic password loss.** Permanent backup loss. Mitigated by writing
   the password down offline and storing in a password manager. See
   `BACKUP.md`.
7. **Argon2 misconfigured for the box.** If hashing takes too long,
   logins feel sluggish. If too short, passwords are weak. Tune via the
   `VOIDCORE_ARGON2_*` env vars; verify ~250ms per hash.

---

## 16. Glossary

- **Node** — A connected client. Numbered 1–N. The BBS metaphor; in
  practice a WebSocket connection.
- **Sysop** — System operator. The board's admin. There is one.
- **NFO** — Information file accompanying a release, traditionally
  authored in CP437 with box-drawing art.
- **Door** — An external program runnable from the BBS menu, classically
  a game.
- **VoidMail** — Private user-to-user messages.
- **One-liner** — Short quip on a shared wall, posted at logoff in many
  classic boards.
- **Carrier** — The modem connection itself. `NO CARRIER` = disconnected.

---

*End of spec.*
