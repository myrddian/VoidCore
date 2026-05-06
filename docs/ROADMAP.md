# ROADMAP.md

The v1/v2 product trajectory for VOIDcore.

This document captures **where v2 goes**. It is not an implementation
spec — that gets written when v2 work begins. The purpose of writing
this now, while v1 is still in spec, is to:

1. Make the v1/v2 boundary explicit so v1 doesn't get scope-crept
2. Constrain v1 protocol decisions so v2 isn't blocked by them
3. Capture design thinking while it's fresh, not reconstruct it later

Read this alongside ADR-016 (v1 architecture), ADR-017 (v2 dynamic
runtime), and ADR-018 (v2 session security) in `DECISIONS.md`.

---

## v1 — what we're shipping

**A self-hosted BBS engine for a niche community.** A single instance
serves the community it was deployed for; not yet a multi-tenant
product. Scope:

- One sysop per instance
- One self-hosted deployment
- A few hundred users at most
- Single fixed BBS layout
- Single region type (text-cell)
- Standard session security (TLS + Argon2id + opaque session tokens)
- The phases laid out in `SPEC.md` §10

v1 success looks like: a community uses it, it's a recognisable
extension of that community, it costs almost nothing to run, it
doesn't fall over.

v1 ships when the §13 acceptance criteria pass.

---

## v1.4 — Screen abstraction + layout-as-data

**The internal-architecture refactor that lets every subsequent
feature land cleanly.** Captured in **ADR-025** and
**`SPEC-screens.md`**.

The current `ScreenRouter.java` is a 2700+ line God Object holding
routing, per-screen state, layout, and theming all in one class.
v1.4 splits it into a slim dispatcher (~150 LOC) plus one class
per screen, plus a `LayoutTree` data structure for layout, plus a
richer theme model for skinning.

### What changes

| Concern | Today | After v1.4 |
|-----------|-----|------|
| **ScreenRouter** | 2700-line God Object | ~150-line dispatcher |
| **Per-screen logic** | Inline in ScreenRouter | One `Screen` class per phase, ~50–200 LOC each |
| **Layout** | Hand-coded `Frames.update()` calls with literal colour names | `LayoutTree` data; renderer applies theme |
| **Theming** | Partially CSS variables (`#41`) | Full YAML theme files: palette + borders + glyphs |
| **Adding a new screen** | Add a `case` in three switch statements | Drop in a new `@Component class implements Screen` |

### What does NOT change

- No protocol changes
- No database changes
- No UX changes
- No new external dependencies
- No template engine (theme stays as data, layout stays in code)
- No hot-reload (redeploy to add a theme)

### Why this is a milestone

The v1.5 documents-substrate work (ADR-023) and the doors framework
(ADR-019) would *both* compound the God Object problem if written
in the existing shape. v1.4 lands first so subsequent work uses the
new pattern from day one — no need to refactor twice.

`LayoutTree` is also v2's `screen.define` payload (ADR-017) in
in-memory form. v1.4 builds the data model that makes the v2
protocol upgrade trivial when v2 begins.

### Implementation milestone (v1.4 ticket bundle)

Three stacked PRs, each independently useful:

1. **PR-A — Screen interface extraction.** Add `Screen`, `Phase`
   (move out of ScreenRouter), `Transition`, `BbsContext`,
   `ScreenRouter` (slim). Migrate existing screens one at a time
   into per-screen classes. No behaviour change.
2. **PR-B — `LayoutTree` + theme expansion.** Add `LayoutNode`,
   `LayoutTree`, `Style`, `LayoutRenderer`. Convert existing 4
   themes (`#41`) to YAML files with palette + borders + glyphs.
   Migrate screens to return `LayoutTree` from `onEnter()`. Audit
   for hardcoded colour literals.
3. **PR-C — Plugin contract documentation.** Document `Screen` as
   a public extension point in `SPEC.md`. Optional: example
   plugin screen demonstrating the contract.

### Sequencing

v1.4 lands **before** the v1.5 documents substrate and **before**
any door framework code (ADR-019). This is the architectural
foundation; everything else builds on it.

---

## v1.5 — Information substrate

**The structural change that takes VOIDcore from "social board
with extras" to "information system that is also social."**
Captured in **ADR-023** (information primacy + faceted navigation)
and **`SPEC-documents.md`** (the document model). Optional
companion: **ADR-024** (Anchor as semantic-retrieval addon).

The shift in framing:

| Dimension | v1 | v1.5 |
|-----------|-----|------|
| **Substrate** | Social activity (chat, threads, VoidMail) | Information (documents, faceted) |
| **File area** | Catalog of releases as a separate primitive | One document `kind` of many; recast as `kind=release` |
| **Announcements** | Sysop-published top-down notices | Document `kind=article` with `frontmatter.pinned=true` |
| **Profiles** | (Planned `#84` screen) | Default landing for `?author=X` facet view |
| **Search** | (Planned `#90` lexical) | Same lexical channel; semantic via Anchor (ADR-024) when enabled |
| **Navigation** | Menu trees | Faceted navigation over a global document pool |
| **Spaces / folders / paths** | n/a | Deliberately not introduced; metadata facets are the structure |

### Why this is its own milestone

This isn't polish (`#84-93`) and it isn't v2 dynamic UI runtime.
It's a substrate decision that:
- Subsumes `#27`/`#81` (file area → release documents)
- Subsumes the announcements primitive (`#26`)
- Re-anchors `#84` (profiles → facet view) and `#90` (lexical
  search → search-as-facet)
- Leaves conversation primitives (`#33`/`#34`/`#35`/`#36-39`/
  `#32`) alone — they stay their own thing
- Stays buildable today, against `SPEC-documents.md`'s contract,
  with no new external service dependencies in v1.5 itself

### Implementation milestone (v1.5 ticket bundle)

Sequenced PRs landing under one milestone:

1. **V6 migration** — `documents`, `document_editors`,
   `document_links`, `document_revisions` tables + tsvector
   trigger. Recast `files` and `bulletins` rows into `documents`.
   Drop old tables.
2. **DocumentRepository + domain types** — Java records, jOOQ
   read/write paths for kind/tags/frontmatter, link-graph parser
   on save.
3. **Document viewer (per-kind renderers)** — howto / article /
   link / glossary / release / note. Inherits the `region-main`
   render path; reuses URL linkifier for `~slug` cross-refs.
4. **Document editor** — multi-step compose for new docs;
   keystroke-menu edit pane for existing docs (matches V5 file
   editor pattern).
5. **Faceted navigation surface** — `[I]nfo` menu key; facet
   pickers (`[K]ind`, `[T]ag`, `[B]y`, `[W]hen`); breadcrumb
   serialisation; "narrow further" auto-suggestion.
6. **Search-as-facet** — folds `#90`'s lexical work into the
   faceted surface; power-user filter syntax (`kind:howto
   tag:samples`).
7. **Linkifier extension** — `~slug` and `~handle/slug` resolve
   to documents; cyan underlined render; backlinks view (`[B]`).
8. **Polish ticket re-anchoring** — `#84`/`#90`/`#92` rebased
   against the new substrate; `#85`/`#86`/`#87`/`#88`/`#89`/
   `#91`/`#93` continue unchanged.

### Anchor integration (v1.6 candidate)

Once Anchor itself ships, ADR-024's HTTP integration unlocks:
- Hybrid lexical + semantic search (RRF fusion)
- The `[?]ask` keystroke on document viewers — talking to a
  document via Anchor's three-agent deliberation, streamed as
  region updates over the existing WS protocol

Feature-flagged via `voidcore.anchor.enabled`. Default off; VOIDcore
is fully functional without it.

---

## v2 — major uplift to a product

**A terminal-aesthetic application runtime, with the BBS as flagship
app.** This is a different category of thing. v2 isn't "v1 plus
features"; it's a structural rewrite of how the system positions
itself.

The shift in framing:

| Dimension | v1 | v2 |
|-----------|-----|-----|
| **What it is** | A feature of a website | A product |
| **Who runs it** | The sysop only | Anyone deploying it |
| **What hosts it** | One self-hosted box | Multiple deploys, possibly some hosted |
| **What's on it** | The BBS | The BBS plus other apps |
| **Layout** | Fixed | Dynamic (server-defined) |
| **Region types** | text-cell only | text-cell, list, form, progress, marquee, image |
| **Caching** | None | Layouts and content cached by ID + TTL |
| **Security** | TLS + Argon2 + bearer token | + X25519 KEX + per-message HMAC + forward secrecy |
| **Doors** | Stub menu entry | First-class via the runtime |
| **Branding** | Single instance | Possibly white-labelable |

v2 is justified only if v1 succeeds and the runtime ambition is real
— if there's actual demand for "I want to deploy this for *my*
community" or "I want to write *my* application on this runtime."
Don't build v2 speculatively.

---

## v2 capability areas

### Dynamic UI runtime

The defining v2 capability. The protocol stops being a renderer
contract for one fixed layout and becomes a UI description language.

**Protocol additions:**

- `screen.define { id, version, layout: { regions: [...] }, cacheable, ttl_seconds }`
  — server emits a layout description; client renders accordingly.
- Region types beyond `text-cell`:
  - `list` — selectable items, client handles up/down navigation,
    selection highlight, viewport-local scrolling. Server gets
    `list.selected { item_id }` events.
  - `form` — multi-field input with tab navigation; client handles
    field focus, validation hints; server gets `form.submitted { fields: {...} }`.
  - `progress` — measurable progress bar, server pushes
    `progress.update { value, max, label }`.
  - `marquee` — horizontal scrolling text, client animates locally.
  - `image` — embed an image; cells encode the image data or a URL.
    Useful for ANSI art galleries, possibly album art.
- Cacheability: `cacheable: true, ttl_seconds: 3600` lets clients
  cache the layout + content. Subsequent encounters with the same
  screen ID skip the round trip if cache is fresh.
- Pre-fetch hints: `screen.prefetch { ids: [...] }` tells the client
  to speculatively fetch likely-next screens.

**Application implications:**

- Doors become first-class: a door is a server-side state machine that
  emits screen definitions for whatever shape it wants.
- Third parties (or future-you) can write applications on the runtime
  without touching the BBS code. A poll, a wiki, a code paste, a
  music player widget — all become apps hosted on the same runtime.
- The BBS itself becomes "the flagship app on the runtime." It's
  still the primary user-facing thing, but it's not the *only* thing.

**Client size impact:** runtime grows from ~1500 lines to ~3000-5000
lines. Still small, but a real engine.

### Cacheable content and pre-fetching

The thing that makes the runtime usable on slow connections.

- Static content (NFOs, announcements, user profiles, the menu structure)
  marked cacheable with long TTLs. Client caches by ID + version.
- Dynamic content (chat, presence, recent posts) marked uncacheable.
- Server hints "you'll likely visit X next" — client speculatively
  fetches X while the user is reading the current screen.
- Result: most navigation between cached screens is instant. Only
  truly dynamic content has any RTT cost.

**Privacy and storage concerns:**

- Cached content lives in IndexedDB (browser local storage with
  quota). Client respects quota and evicts old entries.
- Cached content is per-user; logout clears it.
- The cache must be opaque to other browser code (no readable JSON
  in localStorage). IndexedDB with encryption-at-rest if the threat
  model warrants it.

### Forward-secure session security

See ADR-018 for the full design. Short version:

- X25519 mutual key exchange at session establishment / resume
- Per-message HMAC-SHA256 with monotonic seq counter
- Session key in JS memory only, never localStorage
- Forward secrecy via ephemeral key pairs
- Build-time flavor for dev vs prod (no runtime disable in prod)
- Loopback-only override in dev builds (defence in depth)
- Verifier debugging tool replaces "disable security to debug"
- Loud warnings whenever MAC is off

### Doors as first-class apps

> **Updated 2026-04-29:** doors are pulled forward from v2 into a
> v1.5 milestone. The full protocol (`SPEC-doors.md`) is drafted now;
> v1 implements **Normal mode** (cooperative — door owns a viewport,
> BBS keeps notifications + reserved keystrokes); **Deferred mode**
> (sovereign full-screen) is specced but gated until v2 along with
> the eventual DOS-game wrapper. See ADR-019 + ADR-020.

**v1.5 door framework (#46–#49):**

- Doors register with the BBS via a manifest. In-process Java doors
  ship as part of the BBS jar; networked doors run as sidecar
  processes speaking `voidcore-door-v1` over WebSocket.
- One WS per door, multiplexed across all attached users (every
  message tagged with `session_id`). A door is "one service serving
  N users," not N processes serving one user each.
- The door protocol carries Normal + Deferred message surfaces; the
  v1 BBS rejects `attach{mode: "deferred"}` but the wire is
  forward-compatible.
- Doors get a sandboxed API: paint a granted viewport, read filtered
  input, persist state via the BBS-provided KV store
  (`door_state` table, `user`/`shared`/`global` scopes — see
  ADR-020), emit notifications. They cannot read the BBS DB
  directly; networked doors are isolated by process boundary.
- Reserved keystrokes (`Esc`, `Ctrl+]`, `Ctrl+T`) are intercepted
  by the BBS before forwarding to the door. Safety hatch.
- First door: guess-the-number — exercises every v1 protocol
  message. Pure Java, in-process, Normal mode.

**v2+ extensions:**

- Deferred mode: full-screen takeover. Suspend/resume on
  notification, force-exit via `Ctrl+]`.
- DOS-shim (#51+): DOSBox sidecar wrapping classic DOS doors;
  speaks `voidcore-door-v1` outward, looks like a regular door to the
  BBS. Depends on Deferred mode.

**First v2 door target:** a tiny LORD-alike themed around running an
underground record label. Daily turns, sign artists, drop releases,
beef with rivals. Don't build it until the message board and chat are
solid in v1.

### Admin CLI (`voidcore-cli`)

A Spring Shell client that speaks `voidcore-node-v1` over WebSocket —
same protocol the browser uses, no new server endpoints, no new
auth path. Sysop tools become scriptable; REST is rejected as pure
duplication. See **ADR-021** for the full rationale.

**Forward ticket (#52, post-doors):**

- New Gradle module `cli/` alongside `app/` and a shared
  `protocol/` module holding the sealed `ClientMessage` /
  `ServerMessage` records. Protocol drift becomes impossible —
  Java's exhaustive switch on sealed types fails the build if
  either side adds a variant the other doesn't handle.
- Auth mirrors the browser: handle/password (or `VOIDCORE_HANDLE` /
  `VOIDCORE_PASSWORD` env vars) → `auth.ok` → token in
  `~/.voidcore/session.json` mode 0600 → `auth.resume` on subsequent
  runs.
- Interactive REPL (Spring Shell) and one-shot scriptable mode
  (exit codes, `--json` output for `jq` piping).
- Commands cover the existing sysop surface: `users list/ban`,
  `bulletin add`, `file add`, `broadcast`, `audit tail`, `stats`.
- Bulk patterns get easy:
  `find releases/*.zip | xargs -L1 voidcore file add`.
- Does **not** replace the Makefile — container/infra ops
  (`make backup-full`, `make logs`, `make psql`) need shell
  access to the VM and stay shell-side.
- Default endpoint is the operator's public BBS URL (typically
  CDN-fronted) so a sysop can run ops from anywhere; `--endpoint`
  flag overrides for LAN-direct.

### Federated chat via Matrix bridge

The architectural gap classic-era BBSes never closed: store-and-forward
got federated (FidoNet); realtime chat never did. Modern open-source
BBSes haven't fixed it. VOIDcore in v2 ships **Matrix bridging**
for chat and voidmail. See **ADR-022** for the full rationale.

**Forward ticket (#53, v2):**

- Sidecar bridge process speaking `voidcore-node-v1` against the BBS
  (no new server endpoint — same pattern as the admin CLI in #52)
  *and* the Matrix Application Service API against a homeserver.
- BBS chatroom (`#general` and any future rooms) ↔ Matrix room 1:1.
  The protocol already carries `room_id` per chat message so
  multi-room support lands without a protocol bump.
- BBS user → Matrix room: bridge republishes as
  `@bbs_<handle>:bbs.example.com`. Matrix federation distributes from
  there.
- Matrix user → BBS chat: bridge injects as `<matrix:user@host>`
  prefixed handle so terminal clients can visually distinguish.
- Mentions cross the bridge: `@SYSOP` in Matrix pings the BBS user;
  `@matrix:alice` in BBS pings the Matrix user (example handle).
- VoidMail maps to Matrix DMs: 1:1 BBS voidmail thread ↔ 1:1
  Matrix room.
- Homeserver: bridge can register against an operator-run Conduit
  (lightest option — single Rust binary, sqlite, ~50MB RAM, fits
  alongside the existing compose stack) or an existing third-party
  homeserver via AppService namespace registration.
- Trust boundary documented: bridge sees plaintext on both sides.
  E2EE on the Matrix side is terminated at the bridge per standard
  Matrix bridge practice (mautrix, appservice-irc precedent).
- Identity persistence: a bridge-side table maps stable
  `(user_id ↔ MXID)` so handle changes don't break the mapping.

**Why first-mover matters:** no BBS has shipped Matrix bridging.
This would be the first where a user on Element Web could join the
BBS chat from anywhere on the federated Matrix network with full
mention/DM/scrollback support — without the BBS UI compromising
its terminal aesthetic.

**Rough sizing:** two to three weeks of focused work for an
implementer who knows the Matrix Application Service surface. Our
half (the BBS-side bridge client) is small because chat is already
typed events; the bulk is on the Matrix side.

### Multi-tenancy (possibly)

v2's "external product" framing implies someone other than the original
maintainer might deploy it. That's not the same as multi-tenancy, but it might evolve
into it.

**If multi-tenancy is in scope:**

- Each "board" is a separate logical instance with its own users,
  posts, files, sysop.
- The runtime shell stays consistent; per-board branding (banner,
  one-liners, file area) varies.
- Federation? Maybe. Not a v2 commitment.

**If multi-tenancy is NOT in scope (default position):**

- v2 is "deploy your own instance" not "host multiple boards in one
  deploy."
- Each deploy is independent. No cross-deploy federation in v2.
- Simpler. Right answer for first v2 release.

### Door / app marketplace (definitely not v2)

If v2 succeeds and there's appetite for third-party apps on the
runtime, a marketplace becomes plausible. That's a v3 conversation.
Recording it here so future-me doesn't accidentally scope it into v2:
**not v2 scope.**

---

## What v1 does to keep v2 reachable

These are the v1 protocol commitments that exist purely to leave room
for v2:

1. **Envelope reserves `seq` and `mac` fields.** v1 sends `seq: 0,
   mac: null`. v1 servers accept and ignore. v2 servers enforce.
2. **`screen.define` exists in v1.** v1 servers always emit
   `layout: "default"`. v1 clients render the default layout. v2
   adds varied layout descriptions.
3. **Layout descriptions include cacheability hints.** v1 sets
   defaults that disable caching. v2 enables them.
4. **Region type identifier is part of the protocol.** v1 always uses
   `text-cell`. v2 adds new region types under explicit feature flags.
5. **Protocol version is in every message envelope.** Mismatches are
   detected explicitly. v2 bumps the version cleanly.

Removing any of these from v1 would close off the v2 path. They cost
almost nothing in v1 but are critical for v2.

---

## What I'm explicitly *not* committing to

Recording these to prevent silent scope creep:

- A v3. v3 might happen, might not. Don't write a v3 spec preemptively.
- A SaaS hosted version. Maybe, maybe not. Not v2 scope.
- A mobile app (native). Web on mobile is what we have. Native is
  a different product.
- An admin dashboard separate from the BBS sysop screens. Sysop
  tooling lives in the BBS itself.
- An API for third-party clients. The protocol is private; clients
  are the runtime. If a third-party client appears, it's a fork, not
  a contract.
- Search. v1 explicitly excludes. v2 might add Postgres FTS but it's
  not core.
- Multiple chat rooms. v1 single-room is correct. v2 might add named
  rooms; not committed.

---

## When to revisit this document

- When v1 is feature-complete and the question "what's next?" comes up
- When someone asks to deploy VOIDcore for their community (real
  signal of product demand)
- When a third party expresses interest in writing an app on the runtime
- When new pressure tests of the v2 designs (per the discipline note in
  DECISIONS.md) reveal flaws that need design changes

Don't revisit it just because it feels old. The framing should age
fine; only the specifics need updating when v2 starts.
