# DECISIONS.md

> **Audience:** core contributors, architects, and long-term
> maintainers. ADRs are dense by design. If you are landing on the
> repo for the first time, start with [README.md](README.md) and
> [SPEC.md](SPEC.md); come here when you want to understand *why*
> a particular choice was made.

Architectural Decision Records for VOIDcore. The *why* behind every
non-obvious choice in this repo. Read this when you're tempted to second-
guess something — and update it when you change your mind.

Format: each decision is a section with **Context**, **Decision**, and
**Consequences**. Lightweight ADR. Date stamps so future-you knows when a
decision was made and what was known at the time.

---

## ADR-001 — Backend: Java 21 + Spring Boot

**Date:** 2026-04-28

**Context:** The original spec draft (v1.0) recommended Bun + TypeScript
on the basis that the project is small and the TS ecosystem is "current."
Sysop has 20 years of Java distributed-systems experience and is already
running Aletheia on Spring Boot in the same homelab. Stack consistency
matters more than novelty for a solo project.

**Decision:** Backend is Java 21+ on Spring Boot 3.3+. JDK 21 LTS is the
floor; JDK 25 if available on the deploy box.

**Consequences:**
- Stack consistency with Aletheia. One JVM tuning regime, one set of
  monitoring tools, one familiar deployment story.
- Sealed interfaces + records give the WS protocol proper algebraic data
  types. Switch-pattern-match enforces totality at compile time — catch
  "I added a new message variant and forgot a handler" at compile, not
  in production.
- Bigger memory footprint than Bun (~300MB resident vs ~80MB), but the
  homelab box has memory.
- Slower cold start (~5s vs ~50ms), irrelevant for a long-running
  service.
- Larger Docker image (~250MB JRE base vs ~80MB Alpine + Bun), worth it.
- Loses the "all one language" appeal of Bun + TS. Acceptable.

**Rejected alternatives:**
- Bun + TypeScript: novelty bias, not sysop's strength
- Node + TypeScript: same downsides as Bun, fewer upsides
- Kotlin + Ktor: would be fine, but Spring's ecosystem is deeper and
  matches Aletheia
- Go: nice for WS servers but Java has the edge in protocol-typing
  ergonomics with sealed types

---

## ADR-002 — Concurrency: virtual threads, not reactive

**Date:** 2026-04-28

**Context:** Spring Boot 3.x supports both blocking-with-virtual-threads
(via Spring Web) and reactive (Spring WebFlux). The BBS has a few
hundred concurrent WS connections at most; backpressure isn't really a
concern.

**Decision:** Virtual threads. `spring.threads.virtual.enabled=true`.
WebSocket handlers are blocking, one virtual thread per connection.

**Consequences:**
- Linear, readable handler code. No `Mono.flatMap(...)` chains.
- Cheap per-connection threads — millions are theoretically possible,
  hundreds is a non-issue.
- Familiar debugging: stack traces make sense.
- Lose the explicit backpressure semantics of `Flux<T>`. For chat and
  presence broadcasts, that's a non-issue at this scale.

**Rejected alternatives:**
- WebFlux: overkill for board scale, fights the natural request/response
  shape of the BBS protocol.
- Platform threads + thread pool: works, but virtual threads are the
  right modern answer and free.

---

## ADR-003 — WebSocket flavour: plain Spring WebSocket, not STOMP

**Date:** 2026-04-28

**Context:** Spring offers three layers: `WebSocketHandler` (raw),
STOMP-over-WebSocket (pub/sub framing on top of WS), and reactive
WebFlux WS. STOMP gives topic dispatch for free
(`/topic/chat`, `/topic/presence`).

**Decision:** Plain `WebSocketHandler` with a custom JSON envelope. The
sysop owns the wire format. ~50 lines of `SessionRegistry` + topic
dispatcher.

**Consequences:**
- Full control of the on-the-wire format. Readable in browser devtools.
- No STOMP frame overhead.
- No client-side STOMP library dependency.
- Custom dispatcher needs writing — small, one-time cost.
- Sealed-type protocol fits naturally; would have to wedge STOMP frames
  around it otherwise.

**Rejected alternatives:**
- STOMP: convenient but hides the protocol, dilutes the
  "every-byte-on-the-wire-is-mine" feel of the BBS.
- Reactive WS: see ADR-002.

---

## ADR-004 — Database: PostgreSQL, not SQLite

**Date:** 2026-04-28

**Context:** Spec v1.0 proposed SQLite. SQLite is operationally simpler
(one file, no daemon) and adequate for board scale. But: out-of-band
access (sysop CLI, scripts, future metrics scraper) wants a second
process touching the DB; SQLite's locking story makes that fiddly.
PostgreSQL adds one container's worth of complexity and removes a class
of future operational pain.

**Decision:** PostgreSQL 17 in a sibling container, app DB owned by a
dedicated `voidcore_app` role. Schema managed by Flyway.

**Consequences:**
- Real backup tooling: pgBackRest, WAL archiving, point-in-time recovery
- Multi-process access: sysop CLI, monitoring, ad-hoc psql, all happy
- `CITEXT`, `JSONB`, real `TIMESTAMPTZ`, eventual `tsvector` for search
  — all native, all free
- One extra container running. Negligible RAM hit.
- Slightly more involved backup story than copying a file. Mitigated by
  pgBackRest sidecar.
- Postgres major-version upgrades require pg_upgrade. Acceptable.

**Rejected alternatives:**
- SQLite: simpler now, painful later
- H2 / Derby: embedded, Java-native, but ecosystem and tooling weaker
  than Postgres
- Managed Postgres (Neon, Supabase): defeats the purpose of homelab
  hosting

---

## ADR-005 — Persistence layer: JDBI / JdbcTemplate, not JPA

**Date:** 2026-04-28
**Status:** Superseded by ADR-005a

**Context:** Spring Data JPA is the conventional choice. It generates
queries from method names, manages identity, handles dirty checking. It
also: hides what the database is doing, surprises you with N+1 queries,
and adds entity-management ceremony that doesn't fit the
record-as-domain-object style.

**Decision:** Plain SQL via JDBI 3 (or `JdbcTemplate` if even that is
overkill). Domain objects are Java records. Repos return records.

**Consequences:**
- Queries are explicit. No N+1 surprises.
- Records map naturally to result rows.
- More verbose than `findByHandleAndIsBannedFalse(...)`-style derived
  queries. Acceptable for a project of this size.
- Loses change-tracking; updates are explicit `UPDATE` statements. Fine.

**Rejected alternatives:**
- Spring Data JPA: too much magic, fights records
- jOOQ: type-safe SQL DSL, but the codegen step adds friction; revisit
  if query complexity grows
- MyBatis: works, but JDBI is leaner

---

## ADR-005a — Persistence layer: jOOQ (supersedes ADR-005)

**Date:** 2026-04-28
**Status:** Accepted (supersedes ADR-005)

**Context:** ADR-005 chose JDBI on the grounds that "queries are
explicit" and the codegen step in jOOQ is friction. On reflection, this
under-weighted a real concern: defence-in-depth at the API-to-DB
boundary, especially when AI agents author significant portions of the
code.

JDBI relies on developers using bind parameters everywhere. The failure
mode is one query somewhere written with string concatenation —
`"WHERE handle = '" + handle + "'"` — and you have an injection. The
DB driver can't catch this; by the time the query reaches it, the
string has already been assembled. A code review can catch it; a busy
dev or a future AI agent might not.

jOOQ moves the safety boundary up: the DSL doesn't expose a string-
concat path. You can construct unsafe queries only by explicitly
calling `DSL.sql(...)`, which is grep-able and reviewable. This is
structurally better for a multi-actor codebase.

The codegen step is real friction (one-time setup, runs against a live
schema or a Flyway-driven schema dump in CI), but in exchange you get:
- Compile-time validation of every column name and type against the
  actual schema
- Schema drift becomes a compile error, not a runtime exception
- Type-safe Record / POJO mapping out of the box
- Bind parameters by default, raw SQL only on explicit opt-in
- AI agents are more likely to write safe code in a DSL where unsafe
  code requires ceremony

**Decision:** jOOQ. Codegen runs as part of the Gradle build, taking
the Flyway-applied schema as input via Testcontainers (a throwaway
Postgres instance applies migrations and jOOQ inspects the live
schema). Domain objects remain records; repos use jOOQ's `DSLContext`
internally and return records to the rest of the app.

**Consequences:**
- Defence-in-depth: harder to write injection-vulnerable code by
  accident or AI omission
- Compile-time schema validation: rename a column without updating the
  query, build fails, fast feedback
- Codegen step adds ~10s to clean builds, ~0s to incremental builds
- Build now requires Docker (Testcontainers) for the codegen step.
  Acceptable since the dev env already has Docker.
- jOOQ's free Open Source Edition supports Postgres natively; no
  licence cost.
- Slightly larger learning curve for contributors. Mitigated by the
  DSL being readable to anyone who knows SQL.

**Rejected alternatives (revised list):**
- JDBI: see ADR-005, primary issue is the injection-by-omission risk
- Spring Data JPA: too much magic, fights records, still has its own
  injection risks via JPQL string-building
- MyBatis: XML mappers are a step backwards
- Plain `JdbcTemplate`: same risk profile as JDBI, even less ergonomic

---

## ADR-006 — Migrations: Flyway

**Date:** 2026-04-28

**Context:** Need versioned schema migrations. Two main contenders:
Flyway and Liquibase.

**Decision:** Flyway. SQL files in `src/main/resources/db/migration/`.

**Consequences:**
- Plain SQL files, readable in any editor, diffable in git.
- First-class Spring Boot integration: runs on startup automatically.
- Enforced versioning prevents accidental schema drift.
- Liquibase's XML/YAML changelogs are more portable but less readable;
  the project doesn't need DB portability.

**Rejected alternatives:**
- Liquibase: more abstraction than needed
- Hand-rolled init scripts: don't track applied state, eventually break

---

## ADR-007 — Frontend: TypeScript + esbuild, no framework

**Date:** 2026-04-28

**Context:** The frontend is a terminal renderer. It does not need
component lifecycles, virtual DOM, or state management libraries. The
spec describes a small set of imperative screens. A framework would
fight the mental model.

**Decision:** Plain TypeScript bundled with esbuild (or Bun build, both
fine). One bundle, served statically by Spring Boot from
`src/main/resources/static/`. Built by Gradle as part of `bootJar`.

**Consequences:**
- No framework upgrades to chase
- Tiny bundle (~30KB after minification, mostly the terminal renderer)
- Imperative code reads naturally for a screen-by-screen flow
- No JSX → no React mental model overhead
- Loses the ecosystem of React component libraries — irrelevant, this
  UI deliberately doesn't use components

**Rejected alternatives:**
- React: overkill, fights the imperative screen flow
- Vue: same as React
- Svelte: closest to a fit, but still more machinery than needed
- Lit: tempting for the templating, but the terminal API handles
  rendering natively

---

## ADR-008 — Frontend in same Gradle project, not separate

**Date:** 2026-04-28

**Context:** Two ways to organise the TS frontend: (a) same Gradle
project, frontend builds via Gradle plugin and lands in
`src/main/resources/static/`; (b) separate `frontend/` directory with
its own package.json, built independently, copied in by Dockerfile.

**Decision:** Option (a). Frontend lives at
`app/src/main/frontend/`. A Gradle task invokes the bundler during
`processResources` and outputs to `static/`. Single build command:
`./gradlew bootJar`.

**Consequences:**
- One repo, one build, one image
- Gradle dependency tracking covers both Java and TS sources
- Less moving parts in CI / Docker
- Slightly unusual layout (TS source in a Java module)
- Coupling between front and back at build level — fine for solo
  development

**Rejected alternatives:**
- Separate frontend project: cleaner separation, more setup, multi-stage
  Docker build instead of single-stage
- Pre-built bundle committed to the repo: smallest build complexity, but
  binary-ish artifact in git

---

## ADR-009 — Reverse proxy and TLS: external DMZ Caddy

**Date:** 2026-04-28

**Context:** TLS termination must happen somewhere. Options: (a) a
Caddy container in the BBS compose stack, (b) a Caddy on the BBS VM
host, (c) the existing DMZ Caddy on a separate host.

**Decision:** Option (c). The DMZ Caddy (managed by a separate Ansible
repo) is the single TLS-termination point. The BBS VM exposes plain
HTTP on `127.0.0.1:8080` and is only reachable through the DMZ Caddy.

**Consequences:**
- Single source of truth for cert renewal across the homelab
- BBS stack stays small — no Caddy container to manage
- Internal hop from DMZ Caddy to BBS VM is plain HTTP over a trusted
  private network
- If the DMZ Caddy is compromised, every internal service is exposed.
  Mitigated by treating the DMZ host as security-critical.
- Adds the DMZ Caddy as a deployment dependency: BBS can't be served
  publicly without DMZ Caddy config also being updated.

**Rejected alternatives:**
- In-stack Caddy with its own ACME: double TLS termination, two cert
  renewals, more moving parts
- Direct exposure: makes the BBS stack handle TLS, ACME, security
  headers; not its job

---

## ADR-010 — Backup: pgBackRest sidecar + optional restic

**Date:** 2026-04-28

**Context:** Sysop has no current backup strategy. Need something that
covers (a) accidental DROP / bad migration, (b) hardware failure, (c)
total loss of the homelab. Tooling options range from `pg_dump | gzip
| cron` to enterprise-grade backup platforms.

**Decision:**
- **Layer 1 — pgBackRest** sidecar in the same compose stack. Weekly
  fulls, daily incrementals, continuous WAL archiving, local volume.
  Provides PITR.
- **Layer 2 — restic** sidecar (optional, behind compose profile)
  shipping the pgbackrest repo offsite to B2/S3/SFTP, encrypted.
- **Layer 3 — pg_dump** logical dumps documented but not in compose.

**Consequences:**
- Strong recovery guarantees: PITR within retention window, off-site
  protection against home loss
- Operational complexity higher than `pg_dump` alone — but the runbook
  in `docs/RESTORE-RUNBOOK.md` makes the procedure explicit
- Restic password is a single point of catastrophic failure if lost.
  Mitigated by repeated warnings in docs and by storing the password in
  a password manager *and* on paper.
- Adds one or two containers to the stack. Acceptable.

**Rejected alternatives:**
- `pg_dump` only: easy to set up, no PITR, no offsite protection by
  default, restore from a 24h-old dump means up to 24h data loss
- Barman: similar to pgBackRest but slightly heavier for a single-DB
  setup
- Managed backup service: defeats the homelab premise

---

## ADR-011 — Deployment: Docker Compose, not Kubernetes / bare metal

**Date:** 2026-04-28

**Context:** Single VM, single sysop, single instance. Kubernetes would
be absurd. Bare-metal install (systemd unit for Postgres + systemd unit
for Spring Boot JAR) is also viable but requires more setup per host.

**Decision:** Docker Compose. The deployment artifact is a directory
with `docker-compose.yml`, config files, and a Makefile. Drop on a VM,
`make up`.

**Consequences:**
- Reproducible, version-controlled deployment
- Trivial to spin up a second instance (e.g. for restore verification)
- Volume management is explicit and inspectable
- Adds Docker as a dependency on the host; harmless on Linux VMs
- Slightly heavier resource overhead than systemd + native binaries
- Image rebuilds for app changes — acceptable, fast on the dev box

**Rejected alternatives:**
- Kubernetes: laughably overkill
- Nomad: less overkill, still overkill
- systemd units only: works but per-host setup is more involved

---

## ADR-012 — Sysop bootstrap via env var, not migration

**Date:** 2026-04-28

**Context:** First boot needs a sysop user. Options: (a) Flyway
migration that creates the user with a hardcoded password (terrible),
(b) Flyway migration that reads env vars (Flyway can't do that easily),
(c) Spring `ApplicationRunner` that creates the sysop on first boot if
none exists.

**Decision:** Option (c). The app reads `VOIDCORE_SYSOP_HANDLE` and
`VOIDCORE_SYSOP_INITIAL_PASSWORD` env vars on startup. If no user with
`is_sysop=true` exists, create one. After first login, the sysop
changes their password and the env vars become inert.

**Consequences:**
- Bootstrap is idempotent
- Flyway migrations stay deterministic (no env coupling)
- Initial password is in `.env` until the sysop changes it — sysop
  responsibility to rotate
- If the sysop user is deleted, the next boot recreates one. Probably
  desirable but flag this in docs.

**Rejected alternatives:**
- Hardcoded sysop password: never
- Flyway placeholder substitution: works, couples Flyway to env, awkward
- Manual sysop creation via SQL: easy to forget on a fresh deploy

---

## ADR-013 — Caller numbering: persistent counter starting at 1337

**Date:** 2026-04-28

**Context:** Real BBSes show "you are caller #N" on the login banner.
The number is part of the vibe.

**Decision:** A `counters` table with a `caller_count` row, seeded at
1337. Incremented per successful login. Displayed on the post-login
banner.

**Consequences:**
- Authentic BBS feel
- Persists across restarts — number only ever goes up
- Survives DB restore — counter restores with everything else
- Starting at 1337 is a small wink, not a deception

**Rejected alternatives:**
- Starting at 1: too on-the-nose ("only one previous user")
- Starting at a huge number: dishonest about scale
- No counter: loses the vibe

---

## ADR-014 — Federation address: 23:495/0 (placeholder)

**Date:** 2026-04-28

**Context:** Real FidoNet addresses had structure: `Zone:Net/Node`. The
banner shows one as flavour. Number is currently arbitrary.

**Decision:** `23:495/0` for now, with sysop free to change if a more
meaningful number presents itself. The number is purely cosmetic; no
actual FidoNet protocol is implemented.

**Consequences:**
- Sets the BBS-vibe expectation
- Costs nothing
- Open to change via a config var in a future revision
- A small group of nerds will appreciate it; everyone else won't notice

---

## ADR-015 — Single-node operation, no clustering

**Date:** 2026-04-28

**Context:** Multi-node would require: Redis or Postgres-LISTEN/NOTIFY
for the presence registry, sticky sessions or shared session store,
broadcast fan-out across nodes. The board's expected scale doesn't need
any of that.

**Decision:** Single instance. No clustering. Presence registry is an
in-process `ConcurrentHashMap`. Broadcasts iterate the local session
list.

**Consequences:**
- Massive simplification of the design
- Single point of failure: app crash drops everyone
- Mitigated by Docker `restart: unless-stopped` and a healthcheck —
  recovery is seconds, not minutes
- Vertical scaling only: if board grows past a few thousand
  concurrents, revisit

**Rejected alternatives:**
- Multi-node: premature optimisation
- Active-passive failover: complexity not worth it for a hobby board

---

## ADR-016 — Smart-terminal client architecture for v1

**Date:** 2026-04-28
**Status:** Accepted

**Context:** The earlier spec (v1.1) described a thick TypeScript client
that held screen-flow state, knew about menus and chat and files, and
communicated with the server via a domain message protocol (~30 message
types: `bulletins.list`, `chat.send`, `mb.post.create`, etc). This made
the client large (~2000 lines), tightly coupled to the server's domain,
and difficult to extend with new screens or doors without coordinated
changes on both sides.

Pressure-testing during design revealed several concerns:

- **Latency on slow connections.** International users with 200ms RTT
  would feel every menu transition. Thick client did keystroke-level
  responsiveness but every domain action still round-tripped.
- **Doors as a future feature.** A LORD-alike or label-sim door would
  need its own frontend code in the thick-client model — every door
  shipped client-side. Not viable for a one-developer project.
- **Drift between client and server screen state.** Two state machines
  for "what screen is the user on" inevitably drift. Bug class.

**Decision:** v1 ships a **smart-terminal client**. The architecture:

- Server holds all application logic and screen state. Each connected
  user has a server-side state machine driving what they see.
- Protocol is **render frames + input events**, not domain messages.
  Server sends region-targeted content updates and input mode
  declarations; client paints them and reports keystrokes / line
  submissions.
- Client knows: regions (named display areas with fixed v1 layout),
  input modes (none / keystroke / line / password), local echo for
  line and password modes, version-aware reconnect, side effects
  (sound, title, url, clipboard).
- Client does NOT know: what a menu is, what chat is, what a file is,
  any application concept. It's a runtime, not an app.
- Client retains its painted state across WS reconnects. On reconnect,
  region versions are exchanged; if all match, the server confirms
  sync without resending content. This makes flaky-connection
  reconnects (mobile, train tunnels) invisible to the user.
- A `current_screen` JSONB column on `sessions` tracks where each user
  is in the BBS. On reconnect or server restart, users land back in
  their current area instead of the connect sequence.
- Deep links work via URL fragment intents (`#nfo/track-name`). The
  intent flows through the front door (connect sequence + login) and
  is honored after auth, preserving the brand moment while supporting
  shareable URLs.

**Consequences:**

- Client bundle drops from ~2000 lines to ~1500-2000 lines but the
  *kind* of code changes — terminal mechanics instead of application
  flow.
- Server protocol surface drops from ~30 client message types to ~8.
  Total protocol surface is similar but shaped around rendering, not
  domain.
- Adding new screens is a server-only change. Doors become trivial to
  add.
- Latency on slow connections is masked by client-side keystroke echo
  in the status row, plus local echo for line/password input. The
  BBS-correct "menu transition takes a moment" feel is preserved
  without typing feeling laggy.
- Server restart loses in-progress typing but preserves user position
  via `current_screen`. Acceptable trade.
- Train-tunnel-flakiness: most reconnects send no content (sync match),
  so the user perceives no disruption.

**Rejected alternatives:**

- **Thick client (v1.1 design).** Each screen on the client; ~2000
  lines of `screens/*.ts`. Rejected because doors don't scale, drift
  is a real bug class, and the protocol becomes a sprawl of domain
  messages.
- **Pure thin "dumb rasteriser."** Initial sketch had the client doing
  nothing but painting cells from the server. Rejected because
  200ms-per-keystroke felt broken on real connections, and the design
  didn't survive the train-tunnel stress test.
- **Animation primitives in the protocol.** Initial smart-terminal
  draft included `animate.typewriter`, etc. Rejected because the
  client would need an extending vocabulary of primitives, growing the
  versioning surface. Streaming frames during animations is fine at
  BBS scale.

**Forward compatibility:** the protocol envelope reserves `seq` and
`mac` fields for v2 (ADR-018). The `screen.define` message type accepts
arbitrary layout descriptions even though v1 servers only emit the
default layout (ADR-017). v1 clients must tolerate but ignore these
forward-looking elements.

---

## ADR-017 — v2 dynamic UI runtime (forward-looking)

**Date:** 2026-04-28
**Status:** Accepted (forward-looking; recorded to preserve v1 protocol headroom)

**Context:** v1's smart-terminal architecture (ADR-016) ships with a
single fixed layout and a single region type (text-cell). This is
right-sized for the BBS-as-website-feature scope of v1.

Discussion during design surfaced a more ambitious framing: the BBS is
the first application on a *terminal-aesthetic application runtime*
that could host other text-mode applications (polls, wikis, code paste
viewers, image galleries with text-mode rendering, music player
widgets). Each application defines its own screen layouts and content;
the client interprets layout descriptions as data rather than knowing
them as code.

This is a categorically different product — not "the BBS plus
features" but "a runtime, with the BBS as flagship app." It's
explicitly not in v1 scope. But the v1 protocol must not foreclose it.

**Decision:** v2's defining capability is a **dynamic UI runtime**:

- Server emits layout definitions via `screen.define`. Each definition
  describes named regions with positions, sizes, scrolling rules,
  cacheability, TTL.
- Client implements multiple region types (text-cell, list-with-
  selection, form, progress, marquee, image). Each region type has
  its own protocol for content updates and user interactions.
- Screens are cacheable by ID + version. Clients cache static layouts
  and content, refresh on TTL or explicit invalidation.
- Pre-fetching: server can hint that likely-next screens be cached
  speculatively, removing menu-transition latency entirely for
  navigation between cached screens.
- Doors leverage the runtime: a door is just a server-side state
  machine that emits screen definitions for a custom layout.

**v1 protocol headroom required to support v2 without breaking
changes:**

- The `screen.define` message must exist in v1 even though v1 servers
  only emit one layout. v1 clients must tolerate definitions of layouts
  they don't fully implement (text-cell rendering of a layout still
  works even if the layout has unfamiliar region positions).
- Layout descriptions must include cacheability hints (`cacheable`,
  `ttl_seconds`, `id`, `version`) that v1 servers set to reasonable
  defaults and v1 clients ignore.
- Region types must be a closed extensible set, with `text-cell` as the
  default and v1's only implemented type. v2 region types are added
  with explicit feature flags so v1 clients can refuse unknown types
  rather than crash.

**Consequences:**

- v1's protocol contains forward-compatibility scaffolding that v1
  doesn't exercise. This is a deliberate cost; cheaper now than a
  breaking protocol change later.
- A future contributor (or future-me) reading the v1 codebase will see
  unused fields. This ADR exists specifically to prevent "simplifying"
  them out and breaking v2.
- The v1/v2 boundary is a **major uplift**, not a feature addition.
  Re-shaping the BBS from "fan board" to "runtime + flagship app" is
  the v2 deliverable. Don't sneak v2 features into v1 — finish v1
  first.

**Forward-looking nature:** ADRs typically record decisions for things
being built. This one records a decision for something *not* being
built, in order to constrain v1's design. It should be revisited when
v2 work begins; if v2 thinking has evolved, supersede this ADR rather
than silently changing the v1 protocol.

---

## ADR-018 — v2 session security: mutual key exchange + per-message MAC (forward-looking)

**Date:** 2026-04-28
**Status:** Accepted (forward-looking; recorded to preserve v1 protocol headroom)

**Context:** v1 ships with TLS at the DMZ Caddy plus Argon2id-hashed
passwords plus opaque session tokens. This is appropriate for v1's
threat model (small self-hosted board, sysop-trusted DMZ Caddy, no PII).

For v2's "external product" framing, the threat model expands:

- The system might be deployed by people other than the original sysop
  on infrastructure the maintainer doesn't control.
- A compromised session token (logged accidentally, exfiltrated via
  XSS, captured from a stolen browser profile) currently grants full
  account access until expiry.
- Replay attacks are not currently prevented — a captured WS frame
  could be re-sent.
- The sysop wants to be able to share debug captures of WS traffic
  without exposing live credentials.

These aren't v1 problems. They become real if v2 is positioned as
something deployable by third parties.

**Decision:** v2 adds a **forward-secure session security layer** on
top of v1's existing authentication:

- At successful auth, client and server perform an **X25519 key
  exchange**. Each side generates an ephemeral key pair, exchanges
  public components over the (TLS-protected) WS, derives a shared
  secret. Neither side alone can predict the resulting key.
- The shared secret derives a **session key** via HKDF-SHA256.
- The session key never appears on the wire after derivation. Client
  holds it in JS memory only — *not* localStorage. Server holds it in
  per-connection memory.
- Every subsequent WS message carries `seq` (monotonic counter per
  direction) and `mac` (HMAC-SHA256 of `seq || message_id ||
  body_hash` keyed by the session key).
- Server enforces strict monotonic seq, rejects out-of-order or
  replayed messages with `PROTOCOL_VIOLATION`.
- The persistent `session_token` (in localStorage) becomes a
  permission to *initiate* a new key exchange on resume — not direct
  authentication. Compromising the token doesn't grant immediate
  message-forging capability; the attacker must perform a fresh key
  exchange, which is rate-limited and logged.
- **Forward secrecy:** ephemeral keys are discarded after each
  session. Compromising a session key doesn't expose past traffic.

**Operational concerns and mitigations:**

The MAC layer makes hand-debugging WS traffic harder — captured frames
can't be hand-edited and replayed. Three mitigations:

1. **Build-time flavor for dev vs prod.** Dev builds compile with MAC
   enforcement disabled. Production builds always have it. Production
   binary literally contains no code path that disables MAC. Dev and
   prod use different Gradle profiles producing different artifacts.
2. **Loopback-only runtime override** (defence in depth). Even in dev
   builds, a runtime flag to disable MAC only takes effect for
   connections from `127.0.0.1` / `::1`. Production deploys never see
   loopback connections through the DMZ Caddy.
3. **Verifier debugging tool.** Alongside the MAC implementation,
   ship a small CLI/web tool that takes a session secret and verifies
   or computes MACs for a captured frame. Available to the sysop via
   a privileged API endpoint. This eliminates the temptation to
   disable security to debug — there's a *better* path.
4. **Loud warnings whenever MAC is off.** Dev startup banner shouts
   `*** MAC LAYER DISABLED ***`. Every WS message gets a WARN log
   line. The user-facing connect screen shows a warning that the
   session is insecure. Cost of running with MAC off becomes visible
   noise.

**Consequences:**

- Higher security ceiling appropriate for an externally-deployed
  product.
- Forward secrecy: even compromised long-term session tokens don't
  retroactively expose past sessions.
- Replay protection by construction: each `seq` is one-shot.
- Defence in depth: localStorage compromise no longer grants direct
  session forgery.
- More protocol surface (the `seq` and `mac` envelope fields, key
  exchange messages).
- Build complexity: two Gradle profiles, two artifacts, CI rule that
  prod artifacts come from prod profile only.
- v1 protocol must reserve `seq` and `mac` envelope fields so v2 can
  enable them without breaking changes. v1 clients send `seq: 0` and
  `mac: null`; v1 servers ignore both.

**Rejected alternatives:**

- **Bearer token only (v1 status quo).** Fine for v1 scope; not
  adequate for an externally-deployed product.
- **Client-only random number generation (Nintendo CIC analogue).**
  Original sketch. Rejected because security-through-obscurity is an
  anti-pattern. Same architectural shape (rolling secrets, replay
  prevention) achieved properly via X25519 + HMAC + counter.
- **Single global runtime flag to disable MAC.** A security mechanism
  that can be globally disabled with one flag is a security mechanism
  that will eventually be globally disabled by accident. Build-time
  flavor + loopback-only + loud warnings is structurally honest.
- **Binary protocol for performance.** Rejected explicitly: human-
  readable JSON is a hard requirement for debuggability. The MAC
  fields are added strings; they don't break readability.

**Forward-looking nature:** like ADR-017, this records a decision for
something not yet built, to preserve v1 protocol headroom. The v1
client and server must accept the `seq` and `mac` envelope fields as
optional, ignore their values, and not "simplify" them out of the
protocol.

---

## Meta — design discipline for v2 ADRs

**Date:** 2026-04-28
**Status:** Note

The v2-direction ADRs (ADR-016, ADR-017, ADR-018) all evolved
through deliberate stress-testing during design rather than
first-sketch acceptance. Each went through "seems right" → "what about
X scenario?" → "ok the design needs Y" → "what about Z?" → "ok the
design needs W" before landing in a form that survives operational
reality.

Examples:

- ADR-016: thick client → thin client → "but train tunnels" → smart
  terminal with named regions and version-aware reconnect.
- ADR-017: fixed layouts → "but the runtime ambition" → dynamic
  layouts as data, with v1 reserving the protocol surface.
- ADR-018: "rolling random numbers" → "that's security through
  obscurity" → X25519 + HMAC → "but debugging" → MAC verifier tool +
  build flavors + loopback-only + loud warnings.

When implementing v2, follow the same discipline. Don't accept the
first sketch of any of these designs. Stress-test against:

- 200ms RTT international users
- Mobile users on flaky train/tunnel connections
- Server restarts mid-session
- Long-lived sessions across browser hibernation
- Multiple tabs from the same user
- Concurrent sysop and user activity
- Sysop break-in mid-input
- Operational deployment mistakes (wrong build, wrong env var, wrong
  config file)
- Debug captures shared with non-sysops
- Compromised localStorage / browser extensions
- Adversarial new-user registrations (bots, low-effort abuse)

If the design as written doesn't have an answer for each of these,
revise before implementing. First-sketch designs are starting points,
not contracts.

---

## ADR-019 — Door protocol: Normal/Deferred modes, multiplexed transport

**Date:** 2026-04-29
**Status:** Accepted

**Context:** v1 of SPEC.md treated doors as "Phase 5 stub menu entry."
The actual design needs to land before #46/#47 to keep the protocol
forward-compatible with the eventual DOS-game wrapper. Two questions
need pinning:

1. Who owns the screen during a door session — door or BBS?
2. One WebSocket per playing user, or one WS per door (multiplexed)?

Classic BBS doors (LORD, TW2002) ran as DOS executables connected to
the user via a virtual COM port. Modern stacks (Synchronet, Mystic)
preserve that model: door owns the byte stream, BBS is a pipe. The
upside is compatibility with thirty years of DOS doors. The downside
is the BBS loses control — global notifications, mention popups,
reserved keystrokes can't reach the user mid-door.

**Decision:**

Two interaction modes specced in `voidcore-door-v1`:

- **Normal mode (v1).** Cooperative. BBS grants the door a viewport
  (a region inside `region-main`, i.e. the current main app render
  surface); BBS minimises banner chrome to reclaim space, and still
  owns notifications, status, reserved keystrokes (`Esc`, `Ctrl+]`,
  theme cycle), and session lifecycle. User input is filtered through
  the BBS — door sees only keys not consumed by reserved bindings.
- **Deferred mode (specced, not implemented).** Sovereign. Door owns
  the whole screen. BBS retains exactly two privileges: a force-exit
  keystroke (`Ctrl+]`) and the right to overlay an urgent notification
  (suspending the door via `suspend{}` / `resume{}` messages).

v1 BBS rejects `attach{mode: "deferred"}` with `MODE_NOT_SUPPORTED`.
The wire surface is reserved; v2 flips the gate.

Transport: **one WebSocket per door, multiplexed across all attached
users.** Every message carries a `session_id`. A door is one service
serving N users, not N processes serving one user each. Multiplayer
becomes natural; single-player is multiplex-with-N=1, no special case.

The BBS validates every door message, enforces "door can only address
sessions it currently has attached," substitutes server-side identity
for `scope: "user"` storage operations, and silently drops messages
addressed to unknown sessions.

In-process Java doors and networked sidecar doors use the *same*
protocol — only the transport differs. In-process variant is a SDK
that serialises the same messages via direct method calls.

**Consequences:**

- Protocol stays simple and uniform: one schema, two transports.
- BBS can ship Normal mode + global notification overlay even
  *during* a door session. Mentions, sysop broadcasts, theme cycle
  all keep working — better UX than classic BBS doors.
- Multiplexed transport means one door's crash affects all its
  players. Acceptable: same as a single-process classical door, with
  the same restart semantics.
- Door must be idempotent for messages on already-detached sessions
  ("if unknown session_id, drop"). One-line SDK helper.
- Reattach-after-restart is part of the protocol — `attach.reason
  = "reconnect"` triggers the door to rebuild state from KV.
- Forward-compatible: when v2 ships Deferred mode, no protocol
  bump needed; manifest opts in via `modes_supported: [normal,
  deferred]` and BBS stops rejecting.
- DOS-shim (v3+) will sit at the same protocol layer — DOSBox
  subprocess, ANSI/CP437 → row payload translation, runs in
  Deferred mode. No new contract for the BBS.
- Trust model: doors are *partially trusted* (operator-installed),
  not user-adversarial. In-process doors are trusted code. Networked
  doors are isolated by process boundary.

**Rejected alternatives:**

- *Telnet/COM-port emulation only.* Would maximise DOS-door
  compatibility on day one, but loses BBS control of global UX. The
  Normal-mode design retains compatibility (via Deferred + DOS-shim
  in v3+) without abandoning the BBS's modern feel.
- *Per-user WebSocket.* Each playing user opens its own WS to the
  door. Forces the door to coordinate state across N processes/
  goroutines — the multiplayer case is just a fancy multiplex
  anyway, so cut to the chase.
- *Doors run only in-process.* Simplest but locks out other
  languages and the eventual DOS shim. Spec-once, transport-twice
  costs almost nothing.
- *No reserved keystrokes.* Lets doors take the whole keyboard. A
  misbehaving door becomes unkillable from the user's seat. Hard no.

---

## ADR-020 — BBS as KV provider for doors

**Date:** 2026-04-29
**Status:** Accepted

**Context:** Doors need persistent state. Three plausible models:

1. **Door brings own DB.** Each door author wires JDBC, manages
   migrations, picks a schema. Maximum flexibility, maximum
   per-door operational burden.
2. **Door shares the BBS database.** Door inserts into a `door_state`
   table directly via the same DataSource. Easy but couples doors
   to BBS schema; in-process doors get database access; networked
   doors don't, asymmetric.
3. **BBS exposes a KV API over the door protocol.** Door sends
   `storage.get` / `storage.put` / `storage.del` / `storage.scan`;
   BBS handles persistence, scoping, backups.

**Decision:** Option 3 — namespaced KV store provided by the BBS
through the door protocol. One Postgres table backs it:

```sql
CREATE TABLE door_state (
  door_id     TEXT NOT NULL,
  scope       TEXT NOT NULL CHECK (scope IN ('user', 'shared', 'global')),
  scope_key   TEXT NOT NULL,
  key         TEXT NOT NULL,
  value       JSONB NOT NULL,
  version     BIGINT NOT NULL DEFAULT 1,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (door_id, scope, scope_key, key)
);
```

Three scopes:

- `user` — per-player save state. BBS injects `scope_key = user_id`
  from the current `session_id`; door cannot pass arbitrary user_ids.
- `shared` — cross-user state within one door (multiplayer game state).
- `global` — leaderboards, MOTD, "last winner" — visible to anyone
  using this door.

Compare-and-swap is exposed via `expected_version`. Last-write-wins is
the default. `scan{prefix}` returns paged entries for iteration.

KV is **opt-in.** Manifest declares `storage_used: bbs-kv | own |
none`. Doors that want their own database can have one (asymmetric
in-process vs networked: in-process doors share the BBS DataSource
under their own table; networked doors connect to their own Postgres).
The KV is offered as a courtesy.

**Consequences:**

- Door authors who don't want to think about persistence don't have
  to. Two methods (`get`, `put`) cover 80% of game-state needs.
- Backups are uniform: `door_state` rolls into the same pgBackRest
  stanza as everything else (ADR-010). Operators get door state
  recovery for free.
- Restart semantics are simple: door process dies, KV survives;
  reattach replays state from KV.
- Cross-user coordination has a clean primitive (`shared` scope)
  rather than every door inventing its own sync mechanism.
- Networked doors still get BBS-grade persistence without holding
  database credentials, simplifying ops and reducing blast radius.
- Cost: one INSERT/UPDATE per put, one SELECT per get. At BBS scale
  (a few users, a few games) this is noise. Not a write-heavy load.
- Quota / abuse: advisory limits in v1 (1MB/value, 10MB per
  door×user-scope). Enforce only if anything pathological emerges.
- Schema is simple enough that an evil door can't break it — JSONB
  values are opaque to Postgres validation, but the surrounding
  columns are constrained.

**Rejected alternatives:**

- *Door brings own DB.* Too much per-door burden. The bar to
  authoring a door becomes "set up a Postgres" rather than "write
  game logic." Inhibits the "write a small thing in an afternoon"
  pattern that makes doors fun.
- *Shared BBS database with raw access.* Fine for in-process Java
  doors but breaks the symmetry the protocol-mediated design buys
  us. Networked doors would either get database creds (bad) or be
  second-class (also bad).
- *Redis / external KV.* Adds an operational dependency for what
  is, at homelab scale, a few hundred KV pairs across all doors.
  Postgres handles it fine.
- *Filesystem (per-door directory).* Loses transactional guarantees,
  loses CAS, and doesn't roll into the existing backup story.

---

## ADR-021 — Admin CLI: WebSocket client, not a REST API

**Date:** 2026-04-29
**Status:** Accepted (forward decision — implementation tracked as a
later ticket, see ROADMAP.md)

**Context:** Sysop chores (banning users, posting bulletins, adding
NFO releases, broadcasting messages, tailing the audit log) are all
doable through the browser today, but a command-line client would
be useful for: scripting bulk operations (e.g. `find releases/*.zip
| xargs voidcore file add`), running ops from a terminal without
opening a browser, and piping `audit tail --json` into operator
tooling.

The naive design is a REST API beside the WebSocket. That's wrong
for this codebase — it would duplicate the entire sysop capability
surface (auth, rate limiting, role checks, the JSONB audit log, the
sealed-types totality enforcement) into a parallel endpoint set,
and now there are two paths to keep in sync forever. Worse, REST
gives no security advantage: any URL exposed to "the sysop's CLI"
is also a URL an attacker hits if they grab the token. WS already
has all the protections.

**Decision:** The admin CLI is a Spring Shell application that
speaks `voidcore-node-v1` over WebSocket — the same protocol the
browser uses. No new server endpoints. No new auth path. No REST.

Architectural shape:

- The CLI imports the existing sealed `ClientMessage` /
  `ServerMessage` records as a Gradle dependency. Protocol drift
  is impossible by construction — Java's exhaustive switch on
  sealed types fails to compile if either side adds a message
  variant the other doesn't handle.
- Auth flow mirrors the browser: first run prompts for handle +
  password (or reads `VOIDCORE_HANDLE` / `VOIDCORE_PASSWORD` env vars
  for unattended scripts), receives a session token via
  `auth.ok`, stores it at `~/.voidcore/session.json` mode 0600.
  Subsequent runs `auth.resume` and slide the TTL.
- Every CLI action is dispatched through the same WS handler,
  hits the same sysop-role gate, lands the same
  `sysop_actions` audit row. A `voidcore broadcast "..."` is
  indistinguishable in the audit log from a sysop pressing keys
  in the browser.
- The CLI does **not** replace the Makefile. Container/infra
  operations (`make backup-full`, `make logs`, `make psql`,
  `make secrets`) need shell access to the VM and stay in the
  Makefile. The CLI is for BBS-level operations that go through
  the protocol.
- Repository layout: multi-project Gradle build with a shared
  `protocol/` module holding the sealed types, an `app/` module
  for the BBS server, and a `cli/` module for the Spring Shell
  client. Forces protocol stability — protocol changes are a
  single-module diff that both server and CLI consume.

**Consequences:**

- One auth surface, one rate limit, one audit log. Forever.
- Adding a new sysop command means: (1) new variant in the
  sealed protocol type, (2) handler in the dispatcher, (3) new
  command in `cli/`. The compiler tells you if you skipped
  any of those.
- Scriptable ops:
  `find releases/*.zip | xargs -L1 voidcore file add`
  is the natural shape. JSON output (`--json`) for piping into
  `jq`.
- Token theft from `~/.voidcore/session.json` has the same blast
  radius as token theft from `localStorage` in the browser —
  both are sysop-level. File mode 0600 is the same posture as
  for `.env`.
- The CLI runs anywhere the public BBS URL is reachable
  (CDN → reverse proxy → app), so a sysop can do ops from a
  laptop on cellular without VPN. Optional `--endpoint` flag
  for direct LAN access (e.g.
  `ws://198.51.100.6:8080/ws`) when on-prem.
- New module to build, version, and deploy. Real work — not
  blocking anything else, queued post-doors.

**Rejected alternatives:**

- *REST API beside the WS.* Doubles the auth surface, doubles
  the audit-log plumbing, doubles the rate-limit configuration.
  No security gain — REST endpoints are at least as exposed as
  WS, and our threat model already accounts for token theft.
  Net negative.
- *gRPC admin API.* Same as REST but with binary framing. Adds
  Protobuf as a build dependency for what is, at homelab scale,
  a few dozen RPCs. Not worth the toolchain weight.
- *SSH into the VM and run commands directly against Postgres.*
  Bypasses the audit log, which is the *whole point* of having
  one. Acceptable for emergency ops only.
- *A separate "admin" sub-protocol on a different WS endpoint.*
  Solves nothing — same code path, different URL. Moves
  complexity without removing it.
- *Wait for v2's dynamic UI runtime and have the CLI render the
  remote screens.* Cute but inverts the relationship. The CLI
  needs to *script* the BBS, not be a thin terminal in front of
  it.

---

## ADR-023 — Information primacy: documents-as-substrate, faceted navigation, no folders

**Date:** 2026-04-30
**Status:** Accepted (positioning decision; implementation tracked
as the v1.5 information-substrate milestone, see ROADMAP.md and
`SPEC-documents.md`)

**Context:** Classic BBSes were file-exchange systems with social
features around them. Files were the *substance*; chat and message
bases were the connective tissue. VOIDcore inherits the social
machinery (chat, mentions, NetMail, threads, profiles) but **does
not host files**. The current "file area" (`#27`, extended in `#81`)
is really a curated catalog of releases pointing at external
hosts — useful in a community-catalog context but not a "files" substrate
in the BBS sense.

This leaves the question: if not files, then what is the substance
of this BBS? The answer that fits the system's actual ambition is
**information**: how-tos, glossary entries, articles, link
collections, release notes, personal notes. The classic BBS
information lineage isn't files — it's the FidoNet info echoes,
sysop FAQs, ASCII reference docs, the early Usenet
`comp.lang.c`-style reference threads. Modern web has wikis, Notion,
Obsidian, Logseq, Roam — all building on the same insight, all
mostly absent from BBS-shaped systems.

The natural next move is to add documents as a feature. That is the
wrong move at the wrong level — it preserves the assumption that
"files" or "pages" are still the substrate, with information as a
subordinate concern. The right move is to **invert**: information is
the substrate; everything else is connective tissue.

A second framing question follows: how is information organised?
Three plausible answers all carry assumptions worth rejecting:

- **Filesystem-style paths.** Each doc lives at exactly one path.
  Forces "where do I file this" decisions, makes cross-cutting
  topics homeless, structurally rigid.
- **Wiki-style flat namespace.** Loses the BBS-keystroke aesthetic;
  orphan docs without explicit links go dark.
- **User-spaces as primitive containers.** Forces a structure on
  what is fundamentally just metadata: a "personal space" is just
  the predicate "documents authored by user X." Containerising it
  duplicates the metadata.

The right model is **set-theoretic**: documents are elements in one
global pool, each carrying metadata. Navigation is the act of
intersecting sets defined by that metadata. "Paths" become a
*projection* of facet selections onto a breadcrumb display — they
are not the canonical structure.

**Decision:**

The substance of VOIDcore is **documents**, with the system
positioned as an **information system that is also social**, not a
social system with documents bolted on.

Documents are first-class typed primitives:

- **One global pool.** No `spaces` table, no `path` column, no
  parent/child hierarchy. A `documents` table with rich metadata.
- **Kinds.** `howto`, `article`, `link`, `glossary`, `release`,
  `note`. Kind drives the renderer and the expected `frontmatter`
  shape; doesn't gate storage.
- **Tags.** Free-form lowercase array; multiple per doc; trivially
  added without schema changes.
- **Author + optional editors.** Authors own; editors can write.
  Multi-user editing is metadata, not a separate "shared space."
- **Visibility.** `public` | `private`. Private = author and
  editors only. No spaces; no per-folder ACLs.
- **Body.** Markdown-flavoured, rendered through the existing
  text-cell region renderer; URLs already linkify (`#94`-adjacent
  work).
- **Frontmatter.** JSONB; kind-specific structured fields.

Navigation is **faceted**:

- The user enters an "info" surface and is shown both recent
  documents and the available facets (kind, tag, author, time).
- Selecting a facet narrows the visible set.
- The breadcrumb (e.g. `INFO/kind=howto/tag=samples`) is a
  *display* of the current intersection; identical sets can be
  reached by selecting the same facets in different orders.
- At each level, only facets that meaningfully narrow the current
  set are suggested.

Existing v1 primitives are **recast as facet views or document
kinds**, not preserved as separate features:

| Was | Becomes |
|---|---|
| File area (`#27` / `#81`) | `kind=release` documents; V5 metadata moves into `frontmatter` |
| Bulletins (`#26`) | `kind=article` documents with `frontmatter.pinned=true`; main menu surfaces them via a `?kind=article&pinned=true` facet view |
| User profiles (`#84`) | The default landing for `?author=X` facet view; not a separate screen primitive |
| Full-text search (`#90`) | A facet (textual) on the document pool, not a separate surface |
| Moderation queue (`#92`) | A facet on `documents.status` (`pending` / `published`); sysop sees `?status=pending` |

Conversation primitives **stay separate** and are explicitly
not-documents — their value is ephemerality and addressing, not
curation:

- Chat (`#33`) — ephemeral, room-scoped
- Threads / posts (`#36`–`#39`) — sequential conversation
- NetMail (`#34`) — addressed, private archival
- One-liners (`#32`) — ephemeral wall
- Mentions (`#35`) — annotations across surfaces; extends to
  documents

This separation prevents the substrate from becoming a kitchen
sink. Conversation stays itself; information has its own primitive.

Semantic retrieval (vector search, document-as-agent deliberation)
is **not built into VOIDcore**; it is deferred to optional
integration with the Anchor service (see ADR-024).

**Consequences:**

- **Schema is smaller** than a folders-and-spaces design. One
  documents table, one document_editors table, one document_links
  table for backlinks. No spaces, no paths.
- **Several existing primitives collapse into facet views**, which
  is a net code reduction long-term — the file area, bulletins,
  and the proposed user-profile screen all become document
  filters with kind-specific renderers.
- **No "where do I file this" friction.** The act of authoring a
  document is choosing kind + tags, not choosing a folder.
- **Faceted navigation has a learning curve** that folders don't.
  Mitigated by (a) a default canonical "first descent" view that
  most users will never go past, and (b) the keystroke-menu
  aesthetic — every level renders as a small numbered menu, the
  native BBS idiom; users feel they're descending folders even
  though they aren't.
- **Cross-references stay valuable** even with set-membership-as-
  primary-organisation. The `document_links` table lets us show
  backlinks ("3 documents reference this one") and lets the
  in-doc linkifier turn `~handle/slug` references into clickable
  links — wiki value without committing to wiki structure.
- **Information remains the substance**, social remains the
  connective tissue. The BBS doesn't pretend to be a website
  navigator (paths-as-content) or a CMS (containers-as-content).
- **Forward-compatible with Anchor** (ADR-024). The substrate is
  designed to be ingested into a richer semantic-retrieval service
  later without schema-coupling.
- **Forward-compatible with v2's dynamic UI runtime.** Per-kind
  renderers in v1 are pre-canned by the server; in v2 they could
  emit `screen.define` payloads from the same pool.

**Rejected alternatives:**

- *Filesystem-style paths as canonical storage.* Forces a single
  parent for every doc; cross-cutting topics homeless; "where do I
  file this" anxiety; structurally rigid against tag-based reality.
- *Pure wiki, flat namespace, no faceting.* Loses the BBS keystroke
  aesthetic; orphan docs without explicit links go dark; community
  needs link discipline that doesn't scale.
- *Spaces as primitive containers.* Forces a structure on metadata
  that doesn't need it; "user space" is just `?author=X`;
  containerising duplicates the metadata. Worse, it makes
  shared-space ACL rules a separate concern from per-doc editor
  rules — two systems doing one job.
- *Documents as a feature alongside files / threads / chat.* Keeps
  the substrate question unresolved. Either documents matter and
  should be the substrate, or they don't and shouldn't be added.
- *Build semantic retrieval as part of the substrate.* Duplicates
  Anchor; turns VOIDcore into an LLM service which it isn't;
  couples the canonical store to the retrieval engine. See
  ADR-024.
- *Skip information, stay social.* Acceptable; just records that
  every other BBS made this same call. The point of writing this
  ADR is to say "this is the structural change that takes
  VOIDcore from social-board-with-extras to information-system-
  that-is-social."

---

## ADR-024 — Anchor as optional semantic-retrieval addon (forward decision)

**Date:** 2026-04-30
**Status:** Accepted (forward decision — implementation deferred
until Anchor itself ships and the v1 documents substrate from
ADR-023 is in production)

**Context:** ADR-023 establishes documents as the substrate of
VOIDcore. Documents need retrieval. Three retrieval surfaces
matter:

1. **Lexical.** Bag-of-words over document body + title + tags.
   Postgres `tsvector` + GIN; built-in, exact, deterministic.
2. **Semantic.** Vector embeddings + ANN. Catches "industrial kick
   compression" → "sidechain ducking with parallel processing"
   when the words don't overlap. Requires an embedding model
   pipeline.
3. **Deliberated.** "Talk to a document": pose a question, get a
   first-person response grounded in the document's full argument,
   with visible reasoning. The proposer/critic/synthesiser
   pattern; transparency is the trust mechanism, not decoration.

Lexical we can build today (`#90`'s scope; folds naturally into
the documents substrate). Semantic and deliberated surfaces are
where the work compounds: you need an embedding service, an ANN
index, claim-bearing summarisation, multi-agent prompting,
streaming output, and you need to keep all of it consistent with
the canonical document store.

A separate project — **Anchor** (the Anchor companion repo,
v0 spec dated 2026-04-26, by the same author) — is being designed
to do exactly this work. Anchor's contribution is not "RAG with
vectors": it is *opinionated retrieval that is opinionated about
its consumer*. Hierarchical claim-bearing summarisation
(document → chapters → sections → paragraphs → chunks); two
retrieval shapes (`/validate` for LLM-to-LLM consumers; `/ask` for
human consumers via three-agent deliberation); document-as-agent
as the primary human interaction model.

The same author building the same retrieval layer twice — once in
VOIDcore, once in Anchor — is wasteful and produces two
codebases that will diverge. The clean move is to integrate.

**Decision:**

VOIDcore does **not** build semantic retrieval, document-as-
agent deliberation, or any of the embedding/summarisation pipeline.
These capabilities arrive via **optional integration with Anchor**
when Anchor ships.

The integration shape is **loose-coupled HTTP**:

- VOIDcore owns canonical document storage (`documents`,
  `document_editors`, `document_links`, etc., per ADR-023).
- VOIDcore owns lexical search (Postgres `tsvector` + GIN).
- Anchor runs as a sibling service. It owns derived state: its own
  schema (chapters / sections / paragraphs / chunks / embeddings)
  and its own LM Studio + pgvector deployment.
- An `AnchorClient` (OkHttp + Jackson) calls Anchor's
  `POST /ingest`, `POST /retrieve`, `POST /documents/{id}/ask`.
- Each VOIDcore document carries a nullable
  `anchor_document_id` (UUID) — populated when Anchor ingest
  succeeds; null otherwise.
- Behaviour is **feature-flagged** via `voidcore.anchor.enabled` in
  `application.yml`. Default `false`.

Three integration points:

1. **On document save (when enabled).** Post the body + frontmatter
   to Anchor's `/ingest`. Store the returned UUID on
   `documents.anchor_document_id`. Async; document is searchable
   lexically the instant it's saved, semantically once Anchor's
   pipeline finishes.
2. **On document search (when enabled).** Run lexical
   (`tsvector`) and semantic (`AnchorClient.retrieve`) channels in
   parallel. Fuse via reciprocal rank fusion. Return the merged
   ranked list. When Anchor is disabled or unreachable, lexical
   only — graceful degradation, no error to the user.
3. **On `/ask` (when enabled).** New keystroke `[?]` on a document
   viewer prompts for a question. VOIDcore calls Anchor's
   `/ask`, subscribes to Anchor's SSE stream, and emits proposer
   → critic → synthesiser as live `region.update` payloads to the
   client over the existing WS protocol. Deliberation transcript
   visible in the terminal-aesthetic frame. When disabled, the
   keystroke isn't offered.

The `AnchorClient` is the only point of contact. VOIDcore
schema is unaware of Anchor's internals; Anchor is unaware of
VOIDcore's. Both can evolve independently.

**Consequences:**

- **VOIDcore schema stays simple.** No `embedding vector(768)`
  column, no embedding worker pool, no Ollama sidecar to operate
  unless / until Anchor ships.
- **Two services to operate when enabled.** Anchor runs alongside
  the BBS in the same compose stack (or remote). Docker Compose
  surface grows by one service plus Anchor's Postgres / pgvector;
  the BBS's own Postgres stays single-purpose.
- **Graceful degradation.** Anchor down or disabled → lexical
  search still works; `[?]ask` letter is hidden; users see no
  errors, just fewer features.
- **`[?]ask` becomes a real BBS feature.** Talking to a document
  inside the terminal — proposer streams in, critic appears,
  synthesiser revises — is a primitive nothing else in the BBS
  ecosystem (or modern web) offers in this aesthetic. The
  deliberation transcript renders naturally as a sequence of
  region updates.
- **Both projects stay focused.** VOIDcore doesn't grow LLM
  primitives it shouldn't own. Anchor doesn't grow social /
  conversation primitives it shouldn't own.
- **Forward path stays open.** If Anchor's API stabilises and a
  third party builds an alternative implementation, VOIDcore
  picks it up by configuring a different endpoint — no schema
  change.

**Rejected alternatives:**

- *Build semantic retrieval inside VOIDcore.* Duplicates
  Anchor; turns VOIDcore into an LLM service that owns model
  hosting, embedding pipelines, and prompt engineering. Wrong
  product, wrong scope.
- *Bolt on LangChain / Haystack / Spring AI.* External dependency
  churn, generic retrieval that doesn't get document-as-agent for
  free, replaces a coordinated two-project boundary with
  uncoordinated framework dependence.
- *Skip semantic entirely.* Lexical search alone is a 1990s
  experience. VOIDcore deliberately avoids nostalgia-only
  features when they undermine the product; leaving semantic
  off would be authenticity by accident.
- *Couple to Anchor's schema directly.* Bidirectional schema
  dependency; both projects break together; loses the clean
  service boundary that lets either side evolve.
- *Mandate Anchor at deploy time.* Forces operators to run two
  services. Defeats the "deploy as one Docker Compose stack"
  posture; wrong default for a personal BBS.
## ADR-022 — Federated chat via Matrix bridge (v2 forward decision)

**Date:** 2026-04-29
**Status:** Accepted (forward decision — implementation tracked as a
later ticket, see ROADMAP.md)

**Context:** The BBS lineage has a notable architectural gap: the
classic era federated only the *store-and-forward* messaging (FidoNet
echomail, netmail). Realtime chat never federated. DDIAL was an
island; later multinode chat BBSes were islands. IRC eventually
became a federated realtime protocol but evolved separately from the
BBS lineage and never came back to bridge the gap.

Modern open-source BBSes haven't closed this. Synchronet bridges to
IRC, but IRC's federation model is loose by 2020s standards (server-
trust model, no end-to-end privacy, fragile cross-network identity).
ENiGMA½ has no equivalent. There is no current BBS that participates
in modern federated realtime chat.

Matrix is the obvious modern target. It is, in essence, what FidoNet
would be if redesigned for realtime in 2014: federated, room-based,
opaque to any single homeserver operator, well-specified Application
Service API for protocol bridges, store-and-forward semantics for
offline users, and (when wanted) end-to-end encryption. The
`matrix-appservice-irc` bridge is the canonical reference for
"translate a room-based realtime chat protocol ↔ Matrix room"; no
such bridge exists for any BBS because no BBS exposes its chat in a
bridgeable way.

VOIDcore is well-positioned because chat is already protocol-
mediated, not bytestream. Chat broadcasts are typed events
(`ChatMessage` records with stable user_id, server-side timestamp,
mention syntax, room_id field) carried over the WebSocket. Bridging
to Matrix is a sidecar problem, not a refactor.

**Decision:** Federation of the BBS chatroom (and netmail in the same
breath) is via a Matrix Application Service bridge. Implementation
is **v2 work**; this ADR locks in the v1 protocol shape that keeps
the door open and records the architecture so it doesn't need to be
re-derived later.

The bridge is a separate sidecar process:

```
  [BBS user]                          [Matrix federation]
      │                                       │
      ▼                                       ▼
  BBS WS  ──►  [bridge sidecar]  ◄── Matrix Application Service
                  │       │                   │
                  ▼       ▼                   ▼
              chat    netmail        [homeserver: conduit/dendrite]
```

Concretely:

- Bridge connects to the BBS as a special "bridge" sysop session
  (uses the existing `voidcore-node-v1` protocol — no new server
  endpoint, ADR-021 pattern). Subscribes to chat broadcasts and
  netmail delivery events.
- Bridge runs as a Matrix Application Service against a homeserver.
  Either operator-run (Conduit is the lightest option — single Rust
  binary, sqlite, ~50MB RAM) or against a third-party homeserver via
  a registered AppService with namespace claim.
- BBS chatroom (currently hardcoded `#general`) maps 1:1 to a Matrix
  room (e.g. `#general:bbs.example.com`). The protocol carries `room_id`
  per message so v1 doesn't lock us into a single room.
- BBS user posts → bridge republishes to Matrix room as
  `@bbs_sysop:bbs.example.com`. Matrix federation distributes to
  every joined homeserver.
- Matrix user posts → bridge injects into BBS chat broadcast as
  `<matrix:alice@example.org> ...` so terminal clients can visually
  distinguish federated participants without the BBS UI needing to
  know about Matrix.
- Mentions cross the bridge: `@SYSOP` typed in Matrix pings
  the BBS user; `@matrix:alice` typed in BBS pings the Matrix user.
  Identity table maps stable user_id ↔ MXID.
- Netmail maps cleanly to Matrix DMs. A BBS user sending
  netmail to a federated handle creates / reuses a 1:1 Matrix room.

**v1 protocol shape preserved for v2 viability** (these decisions
already align — recording them so they don't drift):

| Need | v1 status |
|---|---|
| Chat events not bytestream | accepted — typed `ChatMessage` |
| Stable user_id distinct from handle | accepted — `user_id` carried separately |
| Server-side timestamps on every chat msg | accepted — `posted_at` set server-side |
| Per-message `room_id` field | accepted — already in protocol shape, hardcoded `#general` for v1 |
| Mention syntax with stable regex | accepted — `@[A-Za-z0-9_\-.]{3,16}` |
| Per-user mention notifications | accepted — #35 |
| Audit log of every cross-protocol message | will piggyback on `sysop_actions`'s pattern |

**Consequences:**

- **First-mover position.** No BBS has shipped Matrix bridging.
  VOIDcore in v2 would be the first where a user on Element
  Web can join `#general` and chat with terminal-client BBS users,
  with mentions / DMs / scrollback all working cross-protocol.
- **Aesthetic preserved.** Federated users appear in the BBS as
  another set of handles with a `matrix:` prefix marker. No modern-
  web-feature leaks into the terminal UI.
- **Operational dependency.** Needs a Matrix homeserver. Conduit is
  the cheapest (single Rust binary, sqlite-backed, suitable for a
  homelab — adds maybe 50MB RAM and one container's worth of compose
  surface). Or the operator can register the bridge against an
  existing third-party homeserver they already use.
- **Identity sprawl.** Every BBS user effectively gets a Matrix MXID.
  Moderation now has a Matrix dimension — banned BBS user is also
  banned from the Matrix room. Sysop tools extend with
  cross-protocol semantics; ADR for that lands when v2 work begins.
- **E2EE friction.** Cross-protocol bridges and Matrix end-to-end
  encryption play poorly together. Standard practice (mautrix,
  appservice-irc) is to terminate encryption at the bridge boundary
  and re-encrypt to the other protocol. Document the trust boundary
  honestly; users joining `#general` from Matrix need to know the
  bridge sees plaintext.
- **Spec engineering.** Matrix Application Service is well-specified
  but bridges are one of the gnarlier corners of the ecosystem.
  Realistic estimate is two to three weeks of focused work for an
  implementer who knows the Matrix surface — not a weekend project.
  References: matrix.org's `appservice-irc`, the `mautrix-*` family.
  Our half is small because chat is already typed events.
- **Federation gain.** Community members on Matrix can talk to people
  on the BBS terminal client without either side installing the other's
  software. That is the actual product win, not the technical
  novelty.

**Rejected alternatives:**

- *IRC bridge.* Lower hanging fruit (Synchronet does it), but IRC's
  federation model is dated. Cross-network identity is unstable;
  scrollback / DMs / mentions don't translate cleanly. The same
  bridge effort is better spent on Matrix, which gets you IRC
  bridging for free via Matrix's own IRC bridge.
- *XMPP bridge.* XMPP federates but the user base has moved on;
  building bridge infrastructure for a network most contemporary
  users aren't on is poor leverage. Could be a v3 add-on if real
  demand materialises.
- *ActivityPub bridge.* ActivityPub federates social feeds, not
  realtime chat. Wrong protocol shape.
- *Build a homegrown federation protocol for VOIDcore.* The
  worst option. Solves a problem someone solved better and now
  every interlocutor needs custom client support.
- *Skip federation, stay an island.* Acceptable; just records that
  every other BBS made this same call. The point of writing this
  ADR now is to keep the option live without v1 commitments.

---

## ADR-025 — Screen abstraction + layout-as-data (theme-skinnable, no templates)

**Date:** 2026-04-30
**Status:** Accepted (refactor milestone; lands as the v1.4
internal-architecture work — between v1 base and v1.5 information
substrate)

**Context:** `ScreenRouter.java` is currently a 2700+ line God
Object holding four distinct concerns:

1. **Routing** — given a session phase, dispatch input to the right
   handler. (This is what the class is named for.)
2. **Per-screen state machines** — `LOGIN_HANDLE → LOGIN_PASSWORD →
   MENU`; sysop file edit menu → field prompt → back to menu; etc.
3. **Per-screen layout** — banner rows, frames, body content,
   prompts, all hand-coded as Java calls to `Frames.update()`.
4. **Per-screen theming** — colour names hardcoded inline (`"bright_yellow"`,
   `"grey"`); border characters `+`/`-`/`|` baked in.

The class works but every change touches it; reading any one screen
requires scrolling through every other; concerns leak across screens
(one screen's fix breaks another). Worse, it sets a pattern:
forthcoming work — doors (`#46-49`, ADR-019), the documents
substrate (ADR-023, `SPEC-documents.md`) — would compound the
problem if it were written in the existing shape. Each new feature
becomes another section in the God Object.

The user specifically called out two desired structural changes:

- **Separation of concerns.** Each screen owns its logic; the
  router just routes; layout is data not code; theming is data not
  code.
- **Skinning without recompile** — but as an *in-between step*, not
  full template-loadable layout. Theme as data (colours, borders,
  glyphs); layout in compiled code.

**Decision:**

Refactor in three orderable steps, each independently useful, all
landing **before** the documents substrate (ADR-023) work begins:

### Step 1 — `Screen` interface; one class per screen

Extract a `Screen` interface. Each existing screen becomes its own
`@Component`-scanned class implementing it:

```java
public interface Screen {
    Phase phase();
    String name();
    LayoutTree onEnter(BbsContext ctx);
    Transition onKey(BbsContext ctx, String key);
    Transition onLine(BbsContext ctx, String text);
    Transition onCancel(BbsContext ctx);
}
```

`ScreenRouter` becomes a slim dispatcher:

```java
@Component
public class ScreenRouter {
    private final Map<Phase, Screen> screens;
    public ScreenRouter(List<Screen> all) {
        this.screens = all.stream().collect(toMap(Screen::phase, identity()));
    }
    public void onKeystroke(VoidCoreSession s, String key) { ... }
}
```

Estimated impact: ScreenRouter shrinks from ~2700 lines to ~150;
~30 small Screen implementations, each 50–200 lines, each testable
in isolation, each with one responsibility. **No external
behaviour change** — same protocol, same UX, same database. Pure
code-organisation win.

### Step 2 — `LayoutTree` data + theme expansion

Each screen returns a `LayoutTree` from `onEnter()` instead of
calling `Frames.update()` directly. `LayoutTree` is a small data
hierarchy: `Header / Spacer / MetaRow / FrameBox / Body / Footer /
Prompt` nodes carrying text and *abstract* style hints
(`primary` / `accent` / `muted` / `alert`), not concrete colours.

A `LayoutRenderer` walks the tree, applies the active theme, and
emits `Frames.update` calls.

Themes are expanded from "CSS variables only" (`#41`) to
**structured YAML** carrying:

- Palette: abstract style names → CGA colour names
- Border characters: `horizontal`, `vertical`, four corners
- Header glyphs: `header_left`, `header_right`, `bullet`, `prompt`

Sysop drops a new theme YAML in `themes/`, redeploy (no
hot-reload), users see it in the theme picker. Layout stays in
Java; only theme is data.

### Step 3 — Plugin contract (Spring component-scan)

Document `Screen` as a public extension point. A third-party JAR
on the classpath providing a `@Component class WeatherScreen
implements Screen` gets auto-registered on the next deploy. No
runtime plugin loader, no hot-deploy, no scripting interpreter.
**The boring-correct extension surface.**

Sidecar plugins via the door protocol (ADR-019) remain available
for non-JVM extensions; this complements rather than replaces.

### Out of scope

Explicit non-decisions, recorded so future work doesn't drift:

- **No template engine.** No Mustache / Handlebars / Jinja /
  Thymeleaf for layout. Layout stays in compiled Java.
- **No hot-reload.** Theme change requires a redeploy.
- **No scripted screens.** No embedded JS / Lua / Python interpreter
  (rejecting the ENiGMA mozjs model).
- **No grand plugin framework.** Spring component-scan + the door
  protocol are the two extension points; a third "plugin runtime"
  is unnecessary at our scale.

### v2 forward-compatibility

`LayoutTree` is **structurally identical** to v2's `screen.define`
payload (ADR-017). v1.4 builds the tree in-memory and renders it
server-side; v2's protocol upgrade is "expose the tree on the wire
and let the client render." The schema is the same; only the wire
boundary moves. v1.4 makes the v2 protocol upgrade trivial without
committing to it.

**Consequences:**

- ScreenRouter shrinks dramatically; reading one screen no longer
  requires scrolling past 29 others; per-screen tests become
  practical.
- Forthcoming work (doors, documents) lands in the new pattern from
  day one — no need to refactor twice.
- Skinning surface meaningfully larger than current `#41` themes:
  borders, glyphs, palette — all swappable per theme without
  touching screen code.
- Adding a new screen is now a single small file plus a `Phase`
  enum entry — much lower cognitive load.
- `LayoutTree` becomes the *invariant* around which the doors and
  documents work fits. Doors emit `LayoutTree` for their viewport;
  document kind-renderers emit `LayoutTree` for their viewer.
  Single rendering pipeline.
- The v1 → v2 transition (when v2 begins) becomes "expose the
  rendering layer on the wire," not "rewrite every screen."
- Schema doesn't change. Database doesn't change. Protocol doesn't
  change. This is purely an internal refactor with extension
  surface added.

**Cost:**

- 30-ish Screen implementations need writing (one per existing
  screen). Each one mostly mechanical extraction. Estimated 1-2
  weeks of focused work.
- One LayoutTree + LayoutRenderer + theme-loader pass. ~500-800
  LOC new, similar deletion from ScreenRouter.
- Test coverage gain: per-screen tests become possible for the
  first time. (Currently testing one screen requires constructing
  a session and walking through `ScreenRouter`'s switch; with
  per-screen classes, you instantiate the screen and call its
  methods directly.)

**Rejected alternatives:**

- *Keep ScreenRouter; live with the God Object.* Compounds badly
  the moment doors and documents land. Every new feature becomes
  a section in a class no one wants to touch.
- *Full template engine + hot reload of layout files.* Buys hot-
  reload of layout, which is more flexibility than a personal BBS
  needs; introduces a templating engine, error reporting for
  malformed templates, and a watcher process. The user explicitly
  asked for the *in-between step* — theme as data, layout in code.
- *Scripted screens (ENiGMA model — embedded JS).* Way too much
  surface. Embedded interpreter, sandboxing, security review, two-
  language codebase. Power users get more than they need; sysop
  ops surface explodes.
- *Refactor incrementally screen-by-screen as new work touches
  them.* Tempting but produces a codebase where some screens use
  the new pattern and some don't, mixed for an indefinite period.
  A clean cut now, before doors and documents land, costs less.
- *Move all layout to v2's protocol now (skip v1.4, jump straight
  to v2 dynamic UI).* Conflates two decisions. v1 needs the
  refactor regardless of v2; coupling them means v1 can't ship
  without v2. Cleanly separating gives us the architecture win
  immediately and the protocol win when v2 is justified.

---

## ADR-026 — Stack-based screen navigation (push/pop with root-guard)

**Date:** 2026-05-01
**Status:** Accepted (lands as part of v1.4 PR-B; full spec in
`SPEC-screen-navigation.md`)

**Context:** During v1.4 PR-A's screen extraction, every cross-
screen transition went through a bespoke `legacyShow*` bridge on
ScreenRouter — one bridge per (caller, callee) edge in the
navigation graph. After PR-A landed there were ~30 of these
bridges. Each one had to know exactly which paint method to call
on the destination, and "back" navigation was ad-hoc per screen
(some screens called `legacyShowMainMenu`, some restored a previous
list from `SessionState`, some did neither and invented their own
re-paint).

The problems this caused:

1. **Each Screen had to know about its peers.** A new screen meant
   adding a new bridge for every screen that wants to navigate to
   it. Quadratic in the worst case.
2. **"Back" was inconsistent.** Some screens went back to where the
   user came from; some went to a hardcoded destination
   (e.g. main menu) regardless of how the user arrived.
3. **Deep flows accumulated state in session fields.** A user
   navigating MENU → BASES_LIST → THREADS_LIST → THREAD_VIEW →
   COMPOSE_POST_BODY left a trail of `selectedBaseId`,
   `selectedThreadId`, etc. — implicit state that screens read to
   reconstruct what the user was doing. Hard to follow, easy to
   leave stale.
4. **ScreenRouter couldn't shrink** without first solving the
   navigation problem; the bridges *were* the rendering router
   even though they shouldn't have been.

**Decision:** Replace the bridge-mesh with a **per-session stack
of phases**. Top of stack is the active screen. Three operations:

- `push(Phase)` — push and dispatch the new top's `onEnter`
- `pop()` — pop the top, the screen beneath becomes active and
  its `onEnter` re-fires
- `replaceTop(Phase)` — swap the top without firing `onEnter`,
  used only by pre-auth linear flows (login / register)

A `Navigator` interface exposes these operations. `ScreenRouter`
implements it. `BbsContext` provides them as convenience helpers
(`ctx.push(Phase.X)`, `ctx.pop()`).

Two structural decisions follow from this:

- **Root-guard for `pop` on an empty stack.** When the user pops
  the last screen (typically the main menu), the natural reading is
  "I'm done." v1.4 fires `onAuthLogout` automatically. (Other
  behaviours — confirm-and-logout, unkillable root — are deferred
  until there's a second use case.)
- **Pre-auth screens stay outside the stack.** Login / register
  are a linear state machine where "going back" doesn't mean
  popping; they use `replaceTop` to advance step-to-step. After
  auth succeeds, the stack is seeded with `MENU` and normal
  navigation begins. From the user's perspective, the main menu
  has no "back" — pressing back logs them out (the root-guard).

**Consequences:**

- Cross-screen navigation collapses from ~30 named bridges to a
  uniform `ctx.push(Phase.X)`. ScreenRouter shrinks proportionally
  as bridges are deleted.
- Each Screen owns its content; navigation is the router's
  problem. Screens never need to know which other screens exist.
- "Back" becomes a single primitive. Every screen's `[Q]`
  keystroke or Esc handler is `ctx.pop()`. Behaviour is uniform
  across the BBS.
- Deep flows are tractable: the stack *is* the call graph; no
  more reverse-engineering "where did I come from" by reading
  session fields.
- Session-level state fields (`selectedSysopId`,
  `bulletinsCache`, etc.) stay — they're per-screen *data*, not
  navigation history. The stack is navigation; session fields are
  payload.
- The pre-auth exception is small and contained. Login and
  register were always linear; treating them as such avoids
  forcing them into a model that doesn't fit.
- v1.4 forgets the stack on WS disconnect. The persisted
  `current_screen` JSONB stores a single resume target, not the
  whole stack. Acceptable for v1; v2 could persist the stack if
  there's demand.

**Rejected alternatives:**

- *Keep the bridge mesh.* Compounds badly with every new screen.
  Not a real option once the screen count exceeds ~10.
- *Tree-of-screens with parent links.* Each screen declares its
  parent at registration. Works for static hierarchies but breaks
  the moment a screen has multiple legitimate "previous" screens
  (e.g. SYSOP_RELEASE_EDIT is reachable from both SYSOP_RELEASES
  list and from any field-edit screen on pop). Stacks handle this
  naturally; static parent links don't.
- *Push with typed arguments.* `ctx.push(Phase.BULLETINS_VIEW,
  bulletinId)`. Type-safe but introduces a generic type per phase
  or a heterogeneous map. v1.4 punts: state goes through session
  fields, same as today. v1.6 can reconsider with concrete
  evidence of the friction.
- *Persistent stack across reconnects.* Serialise the stack into
  `sessions.current_screen`. Possible but raises questions about
  schema versioning, partial-state serialisation, and replay
  hazards. v1.4 trades persistence for simplicity.
- *Stack-everywhere including pre-auth.* Forces login / register
  to fit a model they don't naturally inhabit. Either you let
  users press back from the password prompt to re-login as
  someone else (security smell) or you special-case anyway. The
  pre-auth exception is the honest separation.

---

## ADR-027 — Cross-session messaging: topic invalidation, no payloads

**Date:** 2026-05-01
**Status:** Accepted (lands as part of v1.4 PR-B; spec section in
`SPEC-screen-navigation.md` §13 — "Messaging")

**Context:** Several screens broadcast updates across sessions:
when a user posts a oneliner, every other user currently on the
oneliners wall should see the new entry without manually
refreshing. Same for chat messages, file-area download counts,
new threads / replies, presence join/leave, and mention
notifications.

The current implementation is *imperative pub/sub*: each writer
walks the global session map, filters by current phase, and pushes
a re-painted frame to each peer:

```java
for (var entry : phases.entrySet()) {
    if (entry.getValue().phase != Phase.ONELINERS) continue;
    sendOnelinersFrame(peer);
}
```

There are six of these loops across `ScreenRouter`, each
hand-written, each duplicating roughly the same iteration shape.
They cement a coupling that the v1.4 refactor is otherwise
trying to remove: Screens shouldn't iterate other sessions, and
ScreenRouter shouldn't know how each screen wants to be repainted.

**The key observation that simplifies the design:** every one of
these broadcast targets is **DB-backed**. Oneliners live in the
`oneliners` table, chat in `chat_messages`, files in `files`,
threads / posts in their own tables. The broadcasts aren't
*delivering data* — they're saying *"the data you're looking at
changed; re-read it."* The DB is the source of truth; the
broadcast is a refresh-trigger.

**Decision:** Cross-session change notification uses a
**topic-based, payload-less, in-process pub/sub bus**. Topics are
strings (e.g. `"oneliners"`, `"chat:#general"`, `"thread:42"`).
Subscribers are sessions, attached automatically when their active
screen declares interest in a topic. Notification is a single
`bus.notify(topic)` call — no payload, no replay, no durability
guarantees.

**Lifecycle integration with the Navigator stack:**

- A Screen declares its topics via `Screen.topics(BbsContext) →
  List<String>` (default: empty).
- `Navigator.push(phase)` → after dispatching `onEnter`, the router
  subscribes the session to `screen.topics(ctx)`.
- `Navigator.pop()` → the router unsubscribes the leaving screen's
  topics before re-firing the new top's `onEnter`.
- `onDisconnect(session)` → `bus.unsubscribeAll(session)`.

**Notification handling:**

- A Screen's `onEvent(BbsContext, String topic) → Transition`
  defaults to "re-fire `onEnter`" — i.e. re-paint with fresh DB
  read. This matches what the existing imperative broadcasts do
  (`sendOnelinersFrame` always re-reads `oneliners.recent(40)`).
- A Screen wanting finer-grained behaviour (e.g. chat appending
  one new line instead of re-painting fifty) overrides
  `onEvent` to handle the topic locally.

**Writer-side:**

```java
// In OnelinersScreen.onLine, after insert:
ctx.publish("oneliners");
// Done. No iteration. No filtering. No broadcastOnelinerWall.
```

The `ctx.publish(topic)` helper is a thin wrapper over
`bus.notify(topic)`.

**Implementation:** in-process `Map<String, Set<VoidCoreSession>>`.
Subscriptions are weak in the sense that `VoidCoreSession.isOpen()`
is checked at delivery time and stale entries are cleaned up. No
external broker, no async semantics, no persistence. Estimated
~80 LOC.

**Targeted notifications (mentions, sysop broadcasts) are NOT
in v1.4:** every current cross-session message in the BBS is a
*topic invalidation* (something on screen X changed). Mentions
(@handle popup) and sysop broadcasts (force-push to every
authenticated session) are *targeted notifications* — different
semantic. v1.4 keeps them in their existing form
(`notifyMentions` in ScreenRouter, sysop broadcast via session
walk); the bus only handles topic invalidation. **When targeted
notifications graduate** (and they will, because they share
infrastructure with mentions), the path is per-user topics
(`user:{id}` subscribed-to on login). Recorded so the design
isn't relitigated.

**Consequences:**

- The six imperative `broadcast*` loops in ScreenRouter delete.
  Each writer publishes to a topic; the bus does fan-out.
  Estimated ScreenRouter shrinkage: ~120 lines.
- Screens own their primary action again. A Screen submits the
  new oneliner, then publishes; cross-session machinery is the
  bus's problem.
- **No payloads = no missed-event bugs.** A delivery failure
  means one user's view is one repaint stale; the next event (or
  the next time they enter the screen) makes it fresh. Idempotent
  by construction.
- The Navigator stack model and the bus model interlock cleanly:
  push subscribes, pop unsubscribes, both happen at the same hook
  point. The "what is this session listening to" answer is
  "whatever its current top of stack subscribed to."
- **Forward-compatible** with payloaded events: when a Screen
  profiles slow under repeated full re-reads, it adds a payload
  type for *that one topic* — backward-incompatible only with
  itself, not the rest of the bus.
- **Forward-compatible** with multi-node deployment: same
  interface, swap the in-process implementation for a Redis /
  NATS / similar broker. Defer until multi-node deployment is
  actually a concern.

**Rejected alternatives:**

- *Keep the imperative broadcast loops.* Doesn't scale (every
  new broadcast adds another hand-written loop), keeps the
  cross-session knowledge in screens / router that shouldn't
  have it, and is exactly the pattern the rest of v1.4 is moving
  away from.
- *Payloaded events from day one.* Solves a problem v1.4 doesn't
  have and adds API surface (typed event classes, payload
  schemas, replay considerations) before there's evidence it's
  needed. Adds complexity that idempotent invalidation doesn't.
- *Spring `ApplicationEventPublisher` as the bus.* Tempting
  (free, framework-native) but couples the BBS's domain
  semantics to Spring's event model — every event becomes an
  `ApplicationEvent` subclass. Our own thin interface makes
  it trivial to swap implementations later (Redis / NATS) and
  keeps domain types out of the framework.
- *Reactive streams (Project Reactor / RxJava).* Massive
  overkill for ~5 broadcast topics with at most a few hundred
  subscribers each. The `Map<String, Set<Session>>` is the
  right size for the problem.
- *Bake targeted notifications into v1.4 too.* Mentions and
  sysop broadcasts use a different semantic (per-user delivery,
  not per-topic); shoehorning them into the same model now
  forces design choices we don't have to make yet. Recorded as
  a forward path (per-user topics, `user:{id}` subscriptions)
  so the conversation has a starting point when it happens.

---

## ADR-028 — Migration discipline: no new `legacyX` bridges

**Date:** 2026-05-01
**Status:** Accepted

**Context:** The v1.4 refactor is splitting `ScreenRouter` into
Screens, Navigator, and BbsServices incrementally. Some screens have
moved; some haven't. To let the migration proceed in small steps
without breaking behaviour, a handful of `legacyX` bridges live on
`ScreenRouter` (e.g. `legacyShowUserList`, `legacyHandleOnelinerSubmit`,
`legacyPersistCurrentScreen` — most of which have already been
removed) and screens reach them via `(ScreenRouter) ctx.router()`.

The bridges have a clear lifecycle: each one disappears when its
caller-side Screen is fully extracted. They are tracked tech debt
with a known path to zero.

The risk is the opposite direction: someone (human or agent) hits
friction during a new piece of work and reaches for a *new* bridge
to get unstuck. Every new bridge is another point of coupling that
has to be torn out later, and it almost always means one of three
real architectural mismatches has gone unaddressed:

1. The screen is trying to hold state that doesn't belong to it
   (typically data caches — see ADR-029).
2. A helper is being added at the wrong layer (it doesn't need
   router state, so it belongs in `BbsServices`).
3. A cross-session broadcast is being written imperatively instead
   of through the bus (see ADR-027).

**Decision:** No new `legacyX` methods on `ScreenRouter`, no new
casts through `ctx.legacyRouter()`, and no new `(ScreenRouter)
ctx.router()` casts in Screen implementations. Existing bridges are
allowed; they're tracked, with a path to deletion. New ones aren't.

When the temptation arises, the agent / engineer:

1. Stops, does not add the bridge.
2. Identifies which of the three mismatches above is in play.
3. Either moves the helper properly (`BbsServices`), introduces the
   missing abstraction (View layer for caches, bus topic for
   broadcasts), or — if none of those fit — writes up the
   architectural gap as a new ADR before proceeding.

The discipline is recorded both as this ADR and as a top-level rule
in `AGENTS.md`, so any agent reading the project at session start
sees it.

**Consequences:**

- The set of `legacyX` methods is monotonically decreasing. Counting
  them is a crude but effective health metric for the v1.4
  migration.
- Pressure to add a bridge becomes pressure to identify and fix the
  underlying architectural gap. Friction is a signal, not a thing
  to route around.
- Reviews have a clear, mechanical reject criterion: a PR that
  introduces a new `legacy*` symbol or a new `ctx.router()` cast
  is rejected unless accompanied by an ADR explaining the
  unavoidable gap.

**Rejected alternatives:**

- *Allow new bridges, just promise to clean them up later.* Doesn't
  work — that's the contract that produced the 3000-line router in
  the first place. The whole point of the discipline is to break
  the "just one more" pattern.
- *Encode the rule as a static analysis check (ArchUnit, etc.).*
  Plausible but premature; the rule is enforceable by review at
  current scale, and the friction itself is the signal we want
  agents to feel rather than have automated away. Worth adding
  later if the codebase grows past what review covers.

---

## ADR-029 — Per-session caches belong to a View layer, not the router

**Date:** 2026-05-01
**Status:** Accepted (depends on ADR-027 message bus being live)

**Context:** `ScreenRouter.SessionState` holds three slots:
`pendingHandle`, `bulletinsCache: List<Bulletin>`,
`filesCache: List<FileRecord>`. Two are misnamed.

The *caches* are the list currently rendered to the user, used so
that when the user types `[5]` the router knows which `Bulletin` /
`FileRecord` they meant. They exist for two reasons that are now
both addressable elsewhere:

1. **Index-to-entity resolution at keystroke time** — solved by
   the screen calling the data layer at keystroke time, not by
   stashing a snapshot at paint time, *provided* the data layer
   is fresh.
2. **Avoid re-querying the DB on every paint** — solved by a cache
   that lives next to the data, not next to the session.

With the message bus (ADR-027) in place, the freshness guarantee
becomes structural: the data layer subscribes to its topic and
drops the cache on `notify`. The screen never holds a list and
never has to think about cache invalidation.

`pendingHandle` is a different thing entirely. It's mid-flow form
state during the linear pre-auth login / register sequence. It
doesn't belong on the router any more than the data caches do — it
belongs to the Screen that's actively walking the user through the
flow.

**Decision:**

1. **Introduce a View layer between repository and Screen.** A
   `BulletinView` (and similarly `FileView` etc.) is a Spring
   singleton that:
   - Wraps the corresponding `Repository` (which stays a stateless
     jOOQ adapter).
   - Holds an in-memory cached list, populated lazily on first
     read.
   - Subscribes to its topic on the message bus and drops the
     cache on `notify`.
   - Exposes the read API the screen actually wants
     (`list()`, `byId(id)`, `page(n)`).

2. **Screens never hold list snapshots.** A screen calls
   `ctx.bulletins().list()` on every paint and on every selection.
   The View answers from cache; the cache is fresh because the bus
   said so (or because nothing has invalidated it).

3. **Index-to-entity resolution becomes index-into-current-list.**
   When the user types `[5]`, the screen does
   `ctx.bulletins().list().get(4)`. Because the bus would have
   re-painted on any change since the user last saw the list, the
   list-as-of-paint and list-as-of-keystroke are the same view.

4. **`pendingHandle` moves to its owning Screen.** The pre-auth
   `LoginHandleScreen` / `RegisterHandleScreen` carries the typed
   handle in its own per-session state (or via a `Transition`
   payload into the next step). It comes off `SessionState`.

5. **The `broadcastX` loops in ScreenRouter delete in tandem.**
   When a writer mutates bulletins, it calls
   `ctx.publish("bulletins")`; the View invalidates; every screen
   currently subscribed re-paints via the default `onEvent`
   behaviour. The peer-cache poking
   (`broadcastFileListUpdate` writing `phases.put(peer, ...)` for
   every other session) goes away — peer caches don't exist any
   more, only the singleton `FileView` cache does, and it's
   already stale-on-notify.

After all four moves, `SessionState` either disappears or shrinks
to just `phase` — and `phase` is already duplicated by the
navigation stack top, making it deletable too.

**Consequences:**

- Screens get smaller and more obviously stateless about data —
  they hold UI state (selected index, scroll position) and nothing
  else.
- The "what's in the cache for this session" question stops being
  asked; the cache is global, not per-session.
- ScreenRouter loses the `bulletinsCache` / `filesCache` /
  `pendingHandle` accessors and the `SessionState` record. More of
  the router's reason-to-exist evaporates.
- Each new data-backed screen brings its own View (one Spring
  bean, ~30 LOC). The pattern is duplicated but each instance is
  trivial.
- Tests for screens stop having to construct fake `SessionState`
  with stub caches. They build a `BbsContext` whose Views are
  test doubles.

**Lifecycle of the migration:**

1. ADR-027's message bus must land first. Without it, a View
   layer would have to either re-query on every read (defeating
   the cache) or invent its own freshness mechanism (re-creating
   the bus).
2. Build the read-side View layer first; migrate
   `BulletinsListScreen` and `ReleasesListScreen` to use it; delete
   the matching cache slot from `SessionState` and the matching
   `legacy*Cache` accessors from ScreenRouter.
3. Move `pendingHandle` into the relevant pre-auth Screen as part
   of that screen's PR-B extraction.
4. When all three slots are gone, `SessionState` itself goes.

**Rejected alternatives:**

- *Cache lives on the repository.* Conflates layers — the
  repository stays a pure DB adapter; cache + bus listening is a
  separate concern. The View layer keeps the repository's mock
  surface tiny in tests.
- *Cache stays per-session, just gets invalidated by the bus.*
  Every session pays for the same query independently when the
  data is global. The View singleton is one query for everyone.
- *Pass the snapshot through the `Transition` chain so the screen
  doesn't need a cache at all.* Works for the next-keystroke case
  but doesn't survive the user wandering around (push another
  screen, pop back, expect the list to be fresh). The View
  approach handles both cases the same way.
- *Keep `pendingHandle` on the router because login / register
  are special.* They're linear pre-auth flows, fine — but linear
  doesn't mean "router state." A screen that owns the flow can
  own its mid-flow data without help.

---

## ADR-030 — Extract NavigationState to remove the `@Lazy Navigator` smell (forward decision)

**Date:** 2026-05-01
**Status:** Forward decision (deferred; tracked smell, not v1.4)

**Context:** PR-B step 13b extracts {@code MentionService} from
{@code ScreenRouter}. Same-room suppression (don't pop a notify on
a peer who's already viewing the screen the mention was posted on)
needs per-session phase information. Today that lives on
{@code ScreenRouter}; {@code Navigator.currentPhase} is the read.

The Spring bean graph that resulted has a cycle:

```
ScreenRouter
   ↓ takes List<Screen>
Screen impls (e.g. OnelinersScreen)
   ↓ take BbsServices
BbsServices
   ↓ takes MentionService
MentionService
   ↓ takes Navigator
Navigator == ScreenRouter   ← back to the top
```

Without intervention Spring can't pick a construction order. The
fix shipped in step 13b is `@Lazy Navigator` on the `MentionService`
constructor parameter — Spring injects a proxy that resolves to the
real `ScreenRouter` on first method call (well after wiring is
done). Functional, idiomatic, and works.

It is also a smell. `@Lazy` is the "I have a circular dependency I
don't want to fix structurally" annotation. Every reader who sees
it has to reconstruct *why* the cycle exists; the next refactor
that touches `MentionService` or `Navigator` risks tripping over it.

**Decision (forward):** Extract a `NavigationState` bean that owns
the per-session navigation stack and provides phase queries. The
shape:

```
NavigationState (bean, leaf)
  - per-session Deque<Phase> stacks
  - currentPhase(session)
  - push(session, phase)
  - pop(session) → Optional<Phase>  (returned phase = leaving)
  - replaceTop(session, phase)
  - clear(session)

ScreenRouter implements Navigator
  - delegates stack ops to NavigationState
  - layers onEnter/onEvent dispatch + bus subscription on top

MentionService takes NavigationState (not Navigator)
  - reads currentPhase only; no mutation
  - no cycle: NavigationState has no deps on Screens / ScreenRouter
```

After the extraction the `@Lazy` comes off and the dep graph is
acyclic.

**When:** Not v1.4. The right moment is after enough of `ScreenRouter`'s
non-navigation responsibilities have moved out (rendering — done;
caches — ADR-029; submit handlers — partly done) that the navigation
core is small enough to extract cleanly. Extracting now would touch
push / pop / replaceTop / Navigator / ScreenRouter all at once with
the rest of the migration in flight — not worth the conflict
surface.

**Trigger:** when (a) `SessionState` deletes per ADR-029 step 4, or
(b) the next time `MentionService` (or any other service) needs
`Navigator` for read-only state queries — that's two callers and
the smell stops being theoretical.

**Consequences (when done):**

- `@Lazy` annotation goes away. One fewer Spring incantation a
  reader has to understand.
- `MentionService` and any future service-layer bean depending on
  navigation state has a clean dep (a leaf bean), not a 3000-line
  god object behind a proxy.
- Tests for `MentionService` get easier — the test stub for
  `NavigationState` is trivial (a `Map<String, Phase>`); stubbing
  `Navigator` requires a fuller fake.
- One more bean in the graph; one less ad-hoc proxy. Net win.

**Rejected alternatives:**

- *Keep `@Lazy` indefinitely.* Works but accumulates as more
  services need read-only navigation queries; each adds another
  `@Lazy` and another opportunity to misread the wiring.
- *Make `Navigator` a smaller interface.* The cycle isn't about
  surface area — `MentionService` only calls `currentPhase` already.
  The problem is that `Navigator`'s impl (`ScreenRouter`) has the
  fat dep graph. Splitting `Navigator` into `NavigatorRead` +
  `NavigatorWrite` doesn't break the cycle because the same bean
  implements both.
- *Have `MentionService` skip same-room suppression.* Removes the
  cycle but ships a behaviour regression — peers see two
  notifications (the inline line + the popup) instead of one.
  Not worth it.

---

## ADR-031 — Layout / Flow rendering mode (V1.5 server-side, V2 on the wire)

**Date:** 2026-05-01
**Status:** Accepted (V1.5 server-side abstraction); V2 wire-format extension is a forward decision

**Context:** Every region the server paints today uses fixed
row-indexed positioning: the server emits {@code Frames.row(N, …)}
with an integer row number, the client renders that row at that
position. Works perfectly for tabular lists (users, files, threads),
fixed banners, and prompts. Doesn't work well for long-form text:

- NFO body in `ReleaseViewScreen` lives in a 64-col fixed-width box,
  manually truncated with an ellipsis if a line overflows. Real
  NFOs are wider than that and the server doesn't even *know* the
  client's actual canvas width.
- Bulletin body in `BulletinViewScreen` — same shape, manual line-
  by-line emission, no wrapping.
- Thread post bodies in `ThreadViewScreen` — same.
- Netmail body in `NetmailReadScreen` — same.

These are all "blob of text the server has, client should display."
Browsers can render a lot more than a fixed terminal grid; the wire
format we have is the bottleneck.

The smart-terminal contract from SPEC §6 ("application logic on
server, client renders frames") is already a display-server
architecture in the X11 / RDP / VNC tradition. The fix is to admit
that frames carry more than rows.

**Decision:** Add a per-region rendering mode. Each region
({@code banner}, {@code main}, {@code prompt}, {@code notify})
declares its mode per paint:

- **`FIXED`** — the existing row-indexed grid. What every region
  uses today. Stays the default forever.
- **`FLOW`** — a small layout-element tree the server hands the
  rendering layer. The renderer turns the tree into something the
  client can paint.

Two phases of delivery:

**V1.5 — server-side abstraction, no wire change.**

Screens build a {@code Layout} (a sealed type, either {@code Fixed}
or {@code Flow}). A server-side renderer walks {@code Flow} trees
and emits the existing {@code Frames.row(…)} wire format at a
server-known canvas width (80 cols by default, overridable per
screen). Client unchanged. The win for V1.5: NFO, bulletin, post,
and netmail bodies stop being awkwardly fixed-grid; the *server*
does the wrapping, but at least it does it from a layout tree
instead of hand-built rows. The abstraction is internal to the
server.

**V2 — wire-format extension, real responsive rendering.**

The protocol envelope grows a {@code frame.flow} variant carrying
the {@code Element} tree directly. Client-side renderer measures
against the actual browser canvas, lays out, and paints. Real
responsive reflow on resize. The same screens that opted into
{@code FLOW} mode in V1.5 immediately benefit; nothing else
changes. This is V2's protocol work, recorded here so the
abstraction shape settles in V1.5 and the protocol extension is
purely additive.

**The vocabulary** — small on purpose. Five elements plus two
decorators covers everything the current "blob of text" cases want
to express without becoming X or Swing.

```
Element ::= VStack(children: List<Element>, gap: int = 0)
          | Text(content: String, style: String)        // single line, no wrap
          | Para(content: String, style: String)        // wraps to canvas
          | Rule                                        // horizontal divider
          | Spacer(rows: int = 1)                       // blank line(s)
          | Padded(child: Element, leftCols: int)       // indent decorator
          | Styled(child: Element, style: String)       // colour-override decorator
```

Containers are vertical only — no `HStack`, no `Grid`. Existing
tabular surfaces (user list, file list, threads list) already work
in `FIXED` mode and will stay there. {@code FLOW} is for stacked-
text content. Adding `HStack` later is additive if a screen
genuinely needs it.

Styles are existing string handles into the theme CSS
({@code phosphor / amber / cga / modern}). No new styling — the
win is *layout*, not appearance.

**The renderer** — V1.5:

The walk is straight-line code, no constraint solver, no
measurement passes:

- {@code VStack(children, gap)} → walk children in order; emit
  {@code gap} blank rows between any two children.
- {@code Text(s, style)} → one {@code Row} with the styled span.
  Truncated at canvas width (no wrap; that's {@code Para}'s job).
- {@code Para(s, style)} → split on hard newlines; for each
  resulting paragraph, word-wrap to canvas width; emit one
  {@code Row} per visual line. Single-newline collapses to a soft
  wrap; double-newline preserves a paragraph break.
- {@code Rule} → one {@code Row} with `repeat("-", canvas)`.
- {@code Spacer(n)} → {@code n} blank rows.
- {@code Padded(child, n)} → render child against a canvas
  narrowed by {@code n}; prefix every emitted {@code Row} with
  {@code n} spaces.
- {@code Styled(child, style)} → render child; rewrite the style
  attribute on every emitted span to {@code style} (the
  innermost {@code Styled} wins).

That's it. ~150 lines of renderer plus the type definitions.

**Per-region mode declaration:**

Frames carry a `mode` field. `FIXED` is the default; omitting the
field is `FIXED`. A screen sending a `FLOW` paint emits:

```json
{"region":"main","mode":"flow","seq":N,"rows":[...]}
```

In V1.5 the `rows` are the server-rendered output of the {@code
Flow} tree at the assumed canvas width. In V2 the same envelope
gains an alternative payload (`tree` instead of `rows`) carrying
the {@code Element} tree directly; the client picks based on
which field is present. Backward-compatible with V1.5 clients —
they ignore unknown variants.

**Consequences:**

- All four "blob of text" screens (NFO, bulletin body, post body,
  netmail body) get cleaner code immediately. The screen returns
  a {@code Flow(VStack(Para(body), …))} instead of hand-emitting
  rows.
- Tabular and prompt screens stay on `FIXED`. No mass migration
  required; opt-in per region per paint.
- Renderer lives next to the screen layer (e.g.
  {@code io.aeyer.voidcore.ws.flow.layout}). It's a leaf — no
  dependencies on screens, repositories, or session state. Trivial
  to test (build a tree, assert the emitted rows).
- V2 protocol extension is now scoped: add the `tree` payload to
  `frame.flow`; client-side renderer is new but contained.
- Editing (`TextArea`, caret/selection state, IME) is *not*
  unlocked by this. That's a separate ADR for V2 with cursor-pos
  events on the wire.
- Themes work unchanged. {@code FLOW} elements use the same style
  string handles {@code FIXED} rows do.

**Rejected alternatives:**

- *Bigger vocabulary (HStack, Grid, Box, Border, etc.) up-front.*
  YAGNI for v1.5; can add later additively. The 7-element kit
  covers the actual pain points.
- *Replace `FIXED` entirely with `FLOW`.* Tabular lists and the
  prompt region are correctly fixed-position. Forcing them through
  a flow renderer adds work without value.
- *Build a real layout engine in V1.5 (constraint solver, real
  measurement passes).* Massive over-engineering for the actual
  use cases. The straight-line renderer above is enough.
- *Skip V1.5, ship straight to V2 with the wire format.* Loses the
  internal-cleanup win and forces the protocol design before the
  abstraction has been exercised. Server-side first means V2's
  protocol can be designed against working code, not theoretical
  shapes.
- *Per-screen mode instead of per-region.* The banner is always
  fixed; the prompt is always fixed; only `main` actually varies.
  Per-region is the correct granularity.
- *Editing primitives (TextArea, etc.) in this ADR.* Different
  semantic — cursor state, input events, IME, selection. Belongs
  in its own ADR alongside the V2 protocol work.

---

## ADR-032 — Screen-owned regions (V2 forward decision)

**Date:** 2026-05-01
**Status:** Forward decision (V2)

**Context:** V1 has four hardcoded regions in the wire protocol —
{@code banner}, {@code main}, {@code prompt}, {@code notify}. The
client has fixed CSS layout for those names. Every screen targets
the same set; no screen can declare "I want a {@code header}, no
banner, and a {@code sidebar}." The region vocabulary is part of
the *server's protocol contract*, not part of any individual
screen's design.

This was the right call for V1 — minimal protocol surface, every
screen renders consistently, the client is dumb about layout.

ADR-031 introduces per-region rendering modes ({@code FIXED},
{@code FLOW}) but keeps the region set itself fixed. That's
correct for V1.5 (server-side abstraction only; wire format
unchanged). When V2 lands the {@code tree} payload (per ADR-031
§5), a related question opens up: *should the region set itself
be part of what a screen declares?*

**Decision (forward):** Yes. In V2, regions become **screen-owned
vocabulary**, not server-protocol vocabulary. A screen declares:

- Which named regions it has (could be just {@code main}; could be
  {@code header} + {@code main} + {@code footer} + {@code sidebar};
  could be anything the screen wants).
- The relative arrangement of those regions (probably a top-level
  layout tree using the same {@code Element} vocabulary from
  ADR-031, scaled up to handle 2D placement — additive, not a new
  vocabulary).
- The mode of each region ({@code FIXED} or {@code FLOW}).

The client renders whatever the screen declares. The four V1 names
become *conventional* — most screens still have a {@code main}
because most screens have a primary content area — but they're not
*reserved*. A `Goodbye` screen that wants a single full-canvas
banner with no input region can declare that. A future
`SysopDashboard` that wants a left sidebar and a main pane can
declare that.

**Consequences (when V2 lands this):**

- The wire envelope's {@code region} field becomes a screen-defined
  string, not an enum-like reserved word.
- The client side renderer no longer hardcodes positions for
  banner/main/prompt/notify. It reads the screen's region
  declaration on push and lays out accordingly.
- Existing V1.5 screens declaring the canonical four-region
  layout keep working unchanged through the V2 transition — V2
  treats the V1 region names as a default convention if a screen
  doesn't declare its own layout.
- This pairs cleanly with ADR-031: ADR-031 defines what's *inside*
  a region ({@code Flow} tree of elements); ADR-032 defines what
  *the regions themselves* are. Same vocabulary scaled up one
  level — a screen's top-level declaration is a tree of regions,
  each of which has a mode and (if FLOW) its own internal tree.

**What stays the same:**

- The `notify` region (transient toasts) probably stays a server-
  reserved name even in V2 — it's a cross-screen capability that
  any screen should be able to fire-and-forget. Recorded here so
  the conversation has a starting point; final call is V2-time.
- {@code BbsContext.send(ServerMessage)} stays the screen's outbox
  — what changes is the *shape* of frames, not the send
  mechanism.
- Themes, styles, frame sequence numbers — unchanged.

**Why forward, not now:**

V1.5 ships ADR-031 server-side only (no protocol change). Adding
screen-owned regions on top of that requires the same V2 protocol
extension (`tree` payload variant) that ADR-031 §5 already plans.
There is no incremental V1.x value in delivering screen-owned
regions without the wire change — every existing V1 screen is
happy with the four canonical regions, and the {@code notify}
region is the only one that's genuinely cross-screen.

So this ADR exists purely to **establish the V2 region model
before any V2 protocol design starts**, so the wire format can be
designed for it from day 1 instead of bolted on later.

**Rejected alternatives:**

- *Keep the four regions hardcoded forever.* Works fine for the
  v1 BBS aesthetic but blocks anything that wants a different
  shape (alternate dashboards, sysop tools with sidebars, screens
  that want no banner). Bakes a UX assumption into the wire
  format that should be a screen concern.
- *Let screens declare regions in V1.5 (server-side-only).* The
  client still has CSS for the four named regions; declaring
  custom regions server-side that the client can't position
  doesn't help anyone. Server-side region declarations need a
  client-side renderer to mean anything — same gating constraint
  as ADR-031's {@code tree} payload. Both wait for V2.
- *One reserved region (`notify`) plus everything else screen-
  owned.* Probably the right end-state, but the boundary between
  reserved and screen-owned is a V2 design decision, not a V1.5
  one. Recorded as a starting point, not pinned.

---

## ADR-033 — VoidCoreSession lifecycle decoupled from WebSocket connection

**Date:** 2026-05-01 (revised 2026-05-02)
**Status:** Accepted; phase 0 (actor substrate) and phase 1 (session-by-token
refactor) landed in this PR.

**Context:** The current `VoidCoreSession` is keyed by `WebSocketSession.getId()`
(the underlying WS connection id). It is created in
`afterConnectionEstablished` and destroyed in `afterConnectionClosed`. All
per-user state — `userId`, `handle`, `currentBulletinId`, `currentReleaseId`,
`currentDocumentId`, `registerDraft`, `netmailDraft`, navigation stack via
`NavigationState` — lives on this object and dies with the WS connection.

The auth-side `Session` is separate: a row in the `sessions` table keyed by an
opaque token (per SPEC §5). It survives WS disconnects; the client persists
the token in localStorage and replays it via `auth.resume` on every reconnect.
When `auth.resume` succeeds, the server reconstructs all the per-user
working state from scratch: re-attach the user, re-fetch
`current_screen` from the DB, push the corresponding `Phase`, refire
`onEnter` so the screen repaints. Every reconnect pays the cost of re-doing
this.

This conflation of "BBS session" with "WebSocket connection" caused a
cascade of small bugs the v1.5 debugging cycle uncovered:

- **Pong-arrival race vs heartbeat-close.** `HeartbeatScheduler` decides to
  close a session for missed pongs; in the same window a pong is sitting in
  Tomcat's frame buffer. The close fires; the pong arrives 50ms later and
  hits `handlePongMessage` with `session-known=false` because
  `afterConnectionClosed` already called `registry.remove`. Hysteresis on
  the close mitigates the symptom; the underlying issue is that the
  VoidCoreSession's lifetime is tied to the wrong thing.
- **"Ghost WS" inbound messages.** Same shape — keystrokes arrive on a WS
  whose VoidCoreSession was just removed. The earlier "force-close stale WS"
  fix was a bandaid that added log noise; the right answer is for the
  VoidCoreSession to outlive the WS so a brief reconnect window doesn't
  invalidate the session.
- **Reconnect cost.** Every transient WS drop runs the full post-auth
  pipeline (read `current_screen`, decode JSON, push the right Phase, fire
  `onEnter`, re-subscribe to bus topics). For a 30-second wifi blip, all
  that machinery runs again. With session-by-token, the in-memory state is
  still there; reconnect is just "re-attach the WS handle".
- **Concurrent multi-tab semantics.** A user with two tabs has two
  VoidCoreSessions, each fully independent. Some operations (mention delivery,
  presence broadcasts) iterate sessions and may double-deliver to the same
  user. That's not a bug per se but it's awkward; if VoidCoreSession is keyed
  by user, multi-tab can be modelled as "one session, multiple WS attachments"
  which is closer to the user's mental model.

The root insight from the user's debugging: **the BBS session is durable;
the WebSocket is transient.** The current code treats them as the same
thing.

**Decision:**

Refactor `VoidCoreSession` to be keyed by the **auth session token** (or its
hash; or a server-allocated session-uuid that maps 1:1 to the auth token).
WebSocket connections attach and detach against an existing VoidCoreSession
instead of creating a new one each time. The auth flow becomes the
allocator: `auth.login` and `auth.register` create a new VoidCoreSession;
`auth.resume` returns the existing VoidCoreSession for that token.

```
First successful auth (login | register):
    SessionRegistry.create(token, user) → VoidCoreSession
    bind WS.getId() ↔ token in a side map for fast lookup
    afterConnectionEstablished registers the binding,
        afterConnectionClosed removes the binding (NOT the VoidCoreSession).

auth.resume on reconnect:
    SessionRegistry.byToken(token) → existing VoidCoreSession (in-memory state intact)
    bind the new WS.getId() to the same VoidCoreSession
    NO need to re-restore current_screen — it's still in the VoidCoreSession's
    NavigationState

Logout / session expiry / GC after N minutes WS-less:
    SessionRegistry.destroy(token) → drops the VoidCoreSession
```

`SessionRegistry`'s public surface gains:
- `getByToken(token)` — primary lookup
- `getByConnection(wsId)` — fast path during message dispatch
- A timer that GCs VoidCoreSessions whose WS has been detached for > N minutes
  (configurable; default 5 minutes seems right — survives a tunnel hop or
  laptop sleep but reaps actual abandonment).

`BbsWebSocketHandler.handleTextMessage` looks up by `wsId`; for `auth.*`
messages it goes through `getByToken`. For all other messages, the WS must
already be bound to an VoidCoreSession (via prior auth or resume) — if it
isn't, drop the message and wait for the client's auth.resume.

`AuthFinaliser` and the resume path are the natural homes for the
allocator/lookup. The auth pipeline becomes:

```java
// auth.login / auth.register
VoidCoreSession s = sessionRegistry.create(newAuthSession.token(), user);
sessionRegistry.bindWs(s, underlyingId);
// ... fire onEnter, return ResumeOk / AuthOk

// auth.resume
VoidCoreSession s = sessionRegistry.byToken(req.token()).orElse(null);
if (s == null) { /* token expired or first connect with stale token */
    sendErr("AUTH_REQUIRED");
    return;
}
sessionRegistry.bindWs(s, underlyingId);
// already-painted state is in s.navState; just confirm with the client
```

**Consequences:**

- **Pong-arrival race disappears.** A pong arriving 50ms after the
  heartbeat close-decision still finds the VoidCoreSession (it's not removed
  on WS close, only the WS binding is). `resetPingCounter` works on
  the still-attached counter; the next tick's increment-and-check uses
  the up-to-date value. Hysteresis becomes less critical (still useful as
  defence-in-depth).
- **"Ghost WS" path goes away.** Inbound messages on a WS whose binding
  was removed look up the VoidCoreSession by token (carried in the envelope
  for auth messages, by previous binding for keystrokes). For keystrokes
  on a WS with no current binding, drop and wait for resume. No
  force-close needed.
- **Reconnect is cheap.** No re-running the post-auth pipeline. The
  VoidCoreSession is already in the right state. `auth.resume` becomes a
  binding update + a "you're at screen X, here's the current paint"
  refresh — version-aware so unchanged regions don't even repaint.
- **Multi-tab is naturally modelled.** One VoidCoreSession per logged-in
  user-token, multiple WS bindings allowed. Deliveries (mention popups,
  presence banners) target the VoidCoreSession; the registry decides which
  bound WS actually receives the frame. Future enhancement: deliver to
  the most-recently-active WS only.
- **GC must be designed.** VoidCoreSessions can outlive their WS, but they
  shouldn't outlive the user's actual session. A periodic sweep reaps
  VoidCoreSessions whose `auth.session` row has expired or whose last WS
  detached > N minutes ago without rebinding. This is a small bit of
  infrastructure but it's clean — the auth-session-row's TTL already
  exists as an authoritative "this session is dead" signal.
- **The `current_screen` JSONB column is still useful**, but its role
  shifts: from "everything we need to restore the screen on reconnect"
  to "fallback if the VoidCoreSession was GC'd before the user came back".
  It becomes the slow-path resume; the fast-path is "VoidCoreSession still
  in memory, just rebind".
- **Test surface increases.** Need tests for: token-based lookup, GC
  timing, multi-WS-per-VoidCoreSession, the "VoidCoreSession was GC'd, what
  happens on auth.resume" path (falls back to current `applyPostAuth`
  behaviour, restoring from `current_screen`).
- **Backwards-compatible during migration.** Both lookup paths
  (by-WS-id and by-token) can coexist; new code uses by-token, legacy
  paths can be migrated one screen at a time. The `Phase` field on
  VoidCoreSession's `NavigationState` is already there; the only change to
  consumer code is "where you previously got VoidCoreSession from
  registry.get(wsId), now you can also get it from registry.byToken".

**Rejected alternatives:**

- *Keep WS-keyed VoidCoreSession; add a longer GC grace period.* Treats the
  symptoms (the pong race, the ghost-WS) without addressing the
  conceptual mismatch. Adding hysteresis everywhere is a slippery slope;
  better to fix the lifecycle once.
- *Move only the navigation stack to a token-keyed cache, leaving the
  rest of VoidCoreSession WS-keyed.* Half-fix. The mention drafts, file
  selection ids, register draft, netmail draft are all per-user state
  that should survive a transient disconnect. Splitting VoidCoreSession is
  more code than just changing the key.
- *Replace VoidCoreSession with the auth Session row entirely.* The auth
  Session is DB-backed, designed for cold persistence (token, user_id,
  expiry, current_screen). Putting hot per-call state (drafts,
  navigation stack, message bus subscriptions) in the DB is wrong:
  high write rate, sync overhead, doesn't fit the data. The right
  answer is two layers — DB session for durability, in-memory
  VoidCoreSession for the working state, both keyed by the same token.
- *Treat each WS as fully independent (current state).* Forces the
  reconnect path to re-restore everything from scratch every time;
  creates the race conditions the v1.5 cycle hit; double-counts
  multi-tab users for delivery semantics. Status quo, kept until this
  ADR's implementation lands.

**Sequencing:**

This refactor is significant but bounded. It lands as a dedicated PR
**after** the v1.5 documents-substrate milestone closes (PRs 4–8).
Doing it earlier would conflict with the editor work in progress;
doing it later would push us into v2 territory where ADR-018's seq/mac
adoption may further reshape sessions. Right after v1.5 is the window.

Estimated scope: ~one week. Touches `SessionRegistry`,
`BbsWebSocketHandler`, `VoidCoreSession` lifecycle, the auth path
(`AuthFinaliser`, `ScreenRouter.onAuthResume`), plus a GC scheduler
sibling to `HeartbeatScheduler`. Existing screens don't change — they
already operate on VoidCoreSession opaquely.

**Implementation note (added 2026-05-02):**

The implementation vehicle is the **actor model**, landed alongside this ADR
in the same PR. Before token-keying the lifetime, the synchronisation
primitive itself had to be cleaned up — the original WS-keyed
`VoidCoreSession` mixed connection state, send-side concurrency, and per-user
working state behind a `volatile` field soup plus a `sendLock`. Two
production bugs traced to that conflation (bus-loop recursion in screen
push; pong-vs-text-frame race in heartbeat) made the substrate work the
prerequisite, not a parallel concern.

Concretely:

- **Phase 0 — actor substrate** (in this PR, lower commits): `VoidCoreSession`
  becomes an interface; concrete state lives in `VoidCoreSession`; a
  `SessionActor` owns a `BlockingQueue<Msg>` and a virtual thread; a
  `SessionProxy` implements the interface and routes every call through
  `actor.submit`. Bus listeners enqueue async on the recipient's actor
  (`enqueueAsync`) so a synchronous publish never crosses a state
  boundary. Re-entrant `submit` (worker-thread fast-path) prevents the
  obvious actor-self-call deadlock. Lifetime still WS-keyed.
- **Phase 1 — token-keyed lifetime** (in this PR, upper commits): the
  ADR-033 decision proper. `VoidCoreSession.id` becomes a stable field
  captured at construction (no longer `underlying.getId()`); `underlying`
  becomes volatile + has `swapUnderlying(WebSocketSession)`;
  `SessionRegistry` gains `byToken` / `byCurrentWsId` indices,
  `rekeyOnAuth(actorId, token)`, a detached pool with TTL sweeper, and
  `attachExistingByToken(token, ws)` for the reconnect path. `AuthFinaliser`
  calls `rekeyOnAuth` after login / register / resume. `HeartbeatScheduler`
  closes the WS only — the actor lives until TTL or explicit logout.
- **Phase 2 — durable in-flight state** (deferred): out of scope. Add when
  a real "in-flight compose survives a server restart" requirement emerges.

The actor was the missing primitive that made phase 1 simple. The
session-by-token refactor on top of `volatile` fields + `sendLock` would
have been threadable but ugly; on top of one-queue-one-thread it's just
"index the actor differently."

---

## How to add a decision

Use ADR-NNN sequential numbering. Include date, context, decision,
consequences, rejected alternatives. Keep each entry short — a paragraph
per section is plenty. If a decision is later overturned, don't delete
the ADR; mark it superseded and add a new ADR that supersedes it.

```
## ADR-NNN — Title

**Date:** YYYY-MM-DD
**Status:** Accepted | Superseded by ADR-MMM | Deprecated

**Context:** ...
**Decision:** ...
**Consequences:** ...
**Rejected alternatives:** ...
```
