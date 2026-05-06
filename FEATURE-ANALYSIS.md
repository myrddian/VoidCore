# FEATURE-ANALYSIS.md

**A curated comparison of ENiGMA½'s feature set against VOIDcore's
design, plus the themes worth pursuing as the engine matures.**

> **Filter applied.** This document is opinionated. The brief is *vibe
> over authenticity, leverage modern web infrastructure, no kitchen
> sink.* Lots of authentic-but-pointless ENiGMA features (XModem/ZModem
> transfer, multi-protocol gateways, ratio systems, DOSEMU door
> integration) are deliberately excluded. The selection is what makes
> VOIDcore feel like *a BBS that people want to log into daily*, not a
> museum exhibit.

---

## ENiGMA½ in one paragraph

A modern open-source BBS (BSD-2 licensed, Node.js) that takes the
kitchen-sink approach by design. Multi-protocol gateways (Telnet, SSH,
NNTP, Gopher, WSS), authenticity-faithful file transfer (XModem,
YModem, ZModem), DOSEMU/QEMU door integration, ANSI art galleries,
achievements, file ratings, ratio systems, hierarchical Gosub menus,
drop-file generation for DOS doors (DOOR.SYS, DORINFO1.DEF), archive
peeking inside ZIPs, time banks, multi-language theming. Strong on
completeness, weak on opinionated curation: "give the operator
everything and let them turn off what they don't want." Reading its
source is the closest thing in 2026 to reading a 30-year accumulated
BBS feature list translated into a modern codebase.

---

## What VOIDcore already has

Shipped or specced today:

- Multinode chat
- Announcements / bulletins
- Private messaging (VoidMail)
- Mentions
- Message bases / threads
- File area (with rich metadata)
- One-liners
- Live presence (data layer)
- Themes
- Sysop tools
- Door framework (see `SPEC-doors.md`)

Everything in this list is feature-complete enough that ENiGMA's
implementation isn't worth lifting from. VOIDcore diverges from ENiGMA
on two structural calls that turn out to matter:

1. **Chat is typed events, not a bytestream.** Means a Matrix bridge
   (see `DECISIONS.md` ADR-022) is a sidecar problem, not a refactor.
   ENiGMA can't do this.
2. **Smart-terminal protocol owns the rendering.** Means VOIDcore
   never had to implement ANSI/CP437 byte-stream protocols. ENiGMA's
   whole transfer-protocol layer is an authenticity tax that VOIDcore
   doesn't pay.

---

## What ENiGMA has that VOIDcore would benefit from — five themes

Grouped so the additions *compose* into a richer BBS rather than
sprawling. Each item has a real BBS-product reason, leverages modern
web infrastructure (WS push, Postgres FTS, JSONB), and has nothing to
do with byte-level fidelity to 1992.

### Theme 1 — Identity & belonging

The BBS-product feel where users feel they're *somewhere*, not just
transiently logged in.

| Feature | Why | Modern impl | Scope |
|---|---|---|---|
| User profiles (`user/<handle>` deep link) | A real "page" per user — handle, location, signature, join date, achievements, post counts, last seen, online indicator. Already a deep-link in the intent system. Foundational dependency for several other items. | `users` aggregate query + `last_callers`. One screen. | S |
| Achievements / awards | Gamify activity. First post, 100 oneliners, longest session of the day, sysop-bestowed custom badges. Display on profile + cite in last-callers feed. | One catalog + join table. Awarder service hooks into existing event sites. | S |
| Watch list / follow | Get a quiet notification when a watched user logs in or posts. Mutuality without the noise of a big social network. | One join table; reuses the existing WS broadcast plumbing. | S |
| Sysop notes per user | Private free-text notes on user records, sysop-only. Essential moderation memory; pairs with achievements since they share the user-profile surface. | One column or sibling table. New `[N]` letter on sysop user-detail. | XS |

### Theme 2 — Situational awareness

What makes a BBS feel *alive*. Modern web makes all of these free.

| Feature | Why | Modern impl | Scope |
|---|---|---|---|
| "What's new since last call" | On login, a one-screen summary: "since you were last on, 4 new voidmail, 12 thread replies, 2 new files, 8 oneliners". Optional drill-down per kind. **The single biggest UX win in this list.** | `last_seen_at` already on user; SQL count-diffs across the relevant tables. <50 LOC. | M |
| Live presence detail | Beyond "X users online" — a "who's online" screen showing every connected user + their *current location* ("in chat", "reading thread 12", "in NFO viewer", "afk 4m"). The data already exists in `current_screen` JSONB. | Read existing data; render a screen. WS push when peers transition. | S |
| Recent activity feed | Rolling "what just happened" log: last 50 events. Light Twitter-feed energy, BBS-flavoured. | Append-only `activity_events` table, broadcast like chat. | M |
| Latest chat preview on menu | Last 2 chat lines on the main menu screen, encouraging drop-ins. | One read of chat tail when rendering menu. | XS |

### Theme 3 — Engagement & atmosphere

Small things that make you *want* to come back. Most can ship in a
single bundle; none individually justifies a PR.

| Feature | Why | Modern impl | Scope |
|---|---|---|---|
| Reactions on content | Lightweight emoji or star reactions on announcements, files, posts, oneliners. Aggregate count + your own state inline. Replaces ENiGMA's "thumbs up files" with something that fits 2026 — discoverable, one-keystroke, addictive. | One `reactions(target_kind, target_id, user_id, emoji)` table; `[R]` keystroke. | S |
| Polls | Sysop-created multi-choice. "What theme should I add next?" Active poll surfaces on the main menu. | Two tables. One sysop tools screen. | S |
| Goodbye / connect-line variety | Random "carrier dropped" on logout, random `CONNECT 14400/ARQ/V42BIS`-style strings on login. Five or ten variants picked at random. Authenticity-flavoured but not authenticity-faithful. | An array. | XS |
| Quote of the day / fortunes | Sysop-curated table of one-liners. Refreshes each login. Lyrics, pithy notes, scene-flavoured quotes. | One table. One read. | XS |

### Theme 4 — Discovery

Helps the BBS scale past the single-handful-of-files thin slice.

| Feature | Why | Modern impl | Scope |
|---|---|---|---|
| Full-text search across content | One menu key, type a query, results across announcements / threads / NFOs / oneliners / own voidmail. Postgres `tsvector` + GIN. Brilliant infrastructure for any BBS that grows. | Postgres FTS is built-in; the work is curating the queryable surface. Per-table `search_vector` columns + triggers. | M |

### Theme 5 — Sysop depth

The current sysop tools cover the operational basics. These add depth
without becoming a kitchen sink.

| Feature | Why | Modern impl | Scope |
|---|---|---|---|
| Audit log query screen | Browse `sysop_actions` from inside the BBS. The events are already written; today they need `psql` to read. | New sysop screen + filter helpers. | S |
| Moderation queues | First-time-poster threshold: new users' first oneliner / first thread / first voidmail goes into a sysop approval queue before broadcast. Default-off; toggle per content type. Cuts spam without locking down the BBS. | One `pending_content` table + `[M]oderate` sysop screen. | M |

---

## What VOIDcore deliberately leaves out (and why)

Per the "vibe over authenticity" filter:

| ENiGMA feature | Why skipped |
|---|---|
| **XModem / YModem / ZModem transfer** | Authenticity-only. No web user expects to download via byte-stream-protocol-emulation. Modern web has direct download links — the existing `external_url` field is the right answer. |
| **Telnet / SSH / NNTP / Gopher gateways** | One transport (WSS) is the right number; the rest are nostalgia for a different era. |
| **DOS door integration via DOSEMU/DOSBox** | Already covered as a v3+ ambition (after Deferred mode). Out of scope for this analysis; see `SPEC-doors.md`. |
| **Ratio systems (upload N MB before downloading)** | Solves a 1995 dial-up bandwidth-allocation problem that doesn't apply. Files are external links; there is no ratio. |
| **Time banks / daily time limits** | Punitive. Doesn't fit "I want users to come back." |
| **Multi-language theming** | A single-instance problem to solve, not an engine concern. Bookmark for if/when there are users who'd benefit. |
| **Hierarchical Gosub menus** | The existing screen flow is already navigable. Nested menus add complexity, not vibe. |
| **Drop-file generation (DOOR.SYS, DORINFO1.DEF)** | Authenticity-only. The VOIDcore door protocol (`SPEC-doors.md`) replaces this with a typed contract. |
| **Archive peeking (look inside ZIPs)** | The catalog rows aren't backed by ZIPs; the metadata fields cover the equivalent surface. |
| **In-BBS ANSI art editor** | Cool but very niche. Save for if there's a real "art crew" forming. |
| **Title-screen randomisation** | One step short of vibe-positive — the current banner is a known landmark. Don't randomise the door to your own house. |

---

## Recommended sequencing

If a single "make VOIDcore feel like a real BBS product" stack of work
were planned, in priority order:

| # | Work | Theme | Why this position |
|---|---|---|---|
| 1 | User profiles | Identity | Table-stakes; everything else benefits from it |
| 2 | What's new since last call | Awareness | Single biggest UX win, low cost |
| 3 | Who's online | Awareness | Uses data that already exists; massive vibe payoff |
| 4 | Activity feed | Awareness | Appends to the awareness theme |
| 5 | Reactions | Engagement | Tiny, ubiquitous, addictive |
| 6 | Achievements + sysop notes | Identity / sysop | Pair naturally; one PR |
| 7 | Full-text search | Discovery | The moment content count exceeds memory's grep-ability |
| 8 | Watch list | Identity | Quiet mutuality, fits the small-community vibe |
| 9 | Audit log + moderation | Sysop depth | Sysop depth, ahead of when actually needed |
| 10 | Atmosphere bundle | Engagement | Small atmospheric items (quotes, polls, connect lines), one PR |

Ten work items. None touch authenticity. All lean into the existing
architecture (typed events, JSONB, Postgres FTS, WS push). The
shortlist takes VOIDcore from "well-built BBS thin slice" to "BBS
people want to log into daily."

---

## Cross-references

- `SPEC.md` — base v1 spec
- `SPEC-doors.md` — door protocol (independent of this work)
- `DECISIONS.md` ADR-019/020 (doors), ADR-021 (admin CLI), ADR-022
  (Matrix bridge) — adjacent forward-looking decisions; this analysis
  does not supersede or amend any of them
- `ROADMAP.md` — v1/v2 product trajectory; the work above slots into
  a "v1.5 BBS-product polish" milestone between the door work and the
  v2 dynamic UI runtime
