# FEATURE-ANALYSIS.md

**A curated comparison of ENiGMA½'s feature set against VOIDcore's
current capabilities, plus a prioritised punch-list of additions worth
making.**

> **Filter applied.** This document is opinionated. The brief was
> "vibe over authenticity, leverage modern web infrastructure, no
> kitchen sink." Lots of authentic-but-pointless ENiGMA features
> (XModem/ZModem transfer, multi-protocol gateways, ratio systems,
> DOSEMU door integration) are deliberately excluded. The selection
> is what makes VOIDcore feel like a *BBS that people want to log
> into daily*, not a museum exhibit.

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

## What VOIDcore already has (skip these)

| ENiGMA has it | We have it |
|---|---|
| Multinode chat | `#33` |
| Announcements | `#26` |
| Private messaging | VoidMail `#34` |
| Mentions | `#35` |
| Message bases / threads | `#36–39` |
| File area | `#27` (with V5 metadata in `#81`) |
| One-liners | `#32` |
| Live presence (data layer) | `#28` |
| Themes | `#41` |
| Sysop tools | `#40` |
| Door framework (specced) | `SPEC-doors.md`, `#46` |

Everything in the table is feature-complete enough that ENiGMA's
implementation isn't worth lifting from. We diverge from ENiGMA on
two structural calls that turn out to matter:

1. **Chat is typed events, not a bytestream.** Means a Matrix bridge
   (`ADR-022`) is a sidecar problem, not a refactor. ENiGMA can't do
   this.
2. **Smart-terminal protocol owns the rendering.** Means we never had
   to implement ANSI/CP437 byte-stream protocols. ENiGMA's whole
   transfer-protocol layer is an authenticity tax we don't pay.

---

## What ENiGMA has that we'd benefit from — five themes

Grouped so the additions *compose* into a richer BBS rather than
sprawling. Each item has a real BBS-product reason, leverages modern
web infrastructure (WS push, Postgres FTS, JSONB), and has nothing to
do with byte-level fidelity to 1992.

### Theme 1 — Identity & belonging

The BBS-product feel where users feel they're *somewhere*, not just
transiently logged in.

| Feature | Why | Modern impl | Scope | Ticket |
|---|---|---|---|---|
| User profiles (`user/<handle>` deep link) | A real "page" per user — handle, location, signature, join date, achievements, post counts, last seen, online indicator. Already a deep-link in our intent system. Foundational dependency for several other items. | `users` aggregate query + `last_callers`. One screen. | S | [#84](../../issues/84) |
| Achievements / awards | Gamify activity. First post, 100 oneliners, longest session of the day, sysop-bestowed custom badges. Display on profile + cite in last-callers feed. | One catalog + join table. Awarder service hooks into existing event sites. | S | [#89](../../issues/89) |
| Watch list / follow | Get a quiet notification when a watched user logs in or posts. Mutuality without the noise of a big social network. | One join table. WS broadcast plumbing already exists from `#28`. | S | [#91](../../issues/91) |
| Sysop notes per user | Private free-text notes on user records, sysop-only. Essential moderation memory; bundled with achievements ticket because they share the user-profile surface. | One column or sibling table. New `[N]` letter on sysop user-detail. | XS | [#89](../../issues/89) |

### Theme 2 — Situational awareness

What makes a BBS feel *alive*. Modern web makes all of these free.

| Feature | Why | Modern impl | Scope | Ticket |
|---|---|---|---|---|
| "What's new since last call" | On login, a one-screen summary: "since you were last on, 4 new voidmail, 12 thread replies, 2 new files, 8 oneliners". Optional drill-down per kind. **The single biggest UX win in this list.** | `last_seen_at` already on user; SQL count-diffs across the relevant tables. <50 LOC. | M | [#85](../../issues/85) |
| Live presence detail | Beyond "X users online" — a "who's online" screen showing every connected user + their *current location* ("in chat", "reading thread 12", "in NFO viewer", "afk 4m"). The data already exists in our `current_screen` JSONB. | Read existing data; render a screen. WS push when peers transition. | S | [#86](../../issues/86) |
| Recent activity feed | Rolling "what just happened" log: last 50 events. Light Twitter-feed energy, BBS-flavoured. | Append-only `activity_events` table, broadcast like chat. | M | [#87](../../issues/87) |
| Latest chat preview on menu | Last 2 chat lines on the main menu screen, encouraging drop-ins. | One read of chat tail when rendering menu. | XS | bundled in [#93](../../issues/93) |

### Theme 3 — Engagement & atmosphere

Small things that make you *want* to come back. Mostly bundled into
one ticket because none individually justifies a PR.

| Feature | Why | Modern impl | Scope | Ticket |
|---|---|---|---|---|
| Reactions on content | Lightweight emoji or star reactions on announcements, files, posts, oneliners. Aggregate count + your own state inline. Replaces ENiGMA's "thumbs up files" with something that fits 2026 — discoverable, one-keystroke, addictive. | One `reactions(target_kind, target_id, user_id, emoji)` table; `[R]` keystroke. | S | [#88](../../issues/88) |
| Polls | Sysop-created multi-choice. "What theme should I add next?" Active poll surfaces on the main menu. | Two tables. One sysop tools screen. | S | bundled in [#93](../../issues/93) |
| Goodbye/connect-line variety | Random "carrier dropped" on logout, random `CONNECT 14400/ARQ/V42BIS`-style strings on login. Five or ten variants picked at random. Authenticity-flavoured but not authenticity-faithful. | An array. | XS | bundled in [#93](../../issues/93) |
| Quote of the day / fortunes | Sysop-curated table of one-liners. Refreshes each login. ÆYER lyrics, pithy notes, music quotes. | One table. One read. | XS | bundled in [#93](../../issues/93) |

### Theme 4 — Discovery

Helps the BBS scale past the single-handful-of-files thin slice.

| Feature | Why | Modern impl | Scope | Ticket |
|---|---|---|---|---|
| Full-text search across content | One menu key, type a query, results across announcements / threads / NFOs / oneliners / own voidmail. Postgres `tsvector` + GIN. Brilliant infrastructure for a personal BBS that grows. | Postgres FTS is built-in; the work is curating the queryable surface. Per-table `search_vector` columns + triggers. | M | [#90](../../issues/90) |

### Theme 5 — Sysop depth

The current sysop tools (`#40`) cover the operational basics. These
add depth without becoming a kitchen sink.

| Feature | Why | Modern impl | Scope | Ticket |
|---|---|---|---|---|
| Audit log query screen | Browse `sysop_actions` from inside the BBS. We already write to it; we can't currently read it without `psql`. | New sysop screen + filter helpers. | S | [#92](../../issues/92) |
| Moderation queues | First-time-poster threshold: new users' first oneliner / first thread / first voidmail goes into a sysop approval queue before broadcast. Default-off; toggle per content type. Cuts spam without locking down the BBS. | One `pending_content` table + `[M]oderate` sysop screen. | M | [#92](../../issues/92) |

---

## What we deliberately leave out (and why)

Per the "vibe over authenticity" filter:

| ENiGMA feature | Why skipped |
|---|---|
| **XModem / YModem / ZModem transfer** | Authenticity-only. No web user expects to download via byte-stream-protocol-emulation. Modern web has direct download links and Bandcamp embeds — our existing `external_url` is the right answer. |
| **Telnet / SSH / NNTP / Gopher gateways** | We have one transport (WSS), it's correct, the rest are nostalgia for a different era. |
| **DOS door integration via DOSEMU/DOSBox** | Already covered in our roadmap as v3+ (after Deferred mode). Out of scope for this analysis. See `SPEC-doors.md`. |
| **Ratio systems (upload N MB before downloading)** | Solves a 1995 dial-up bandwidth-allocation problem that doesn't apply. Our files are external links; there is no ratio. |
| **Time banks / daily time limits** | Punitive. Doesn't fit "I want users to come back." |
| **Multi-language theming** | One-community problem. Bookmark for if/when there are users who'd benefit. |
| **Hierarchical Gosub menus** | Our screen flow is already navigable. Adding nested menus adds complexity, not vibe. |
| **Drop-file generation (DOOR.SYS, DORINFO1.DEF)** | Authenticity-only. Our door protocol (`SPEC-doors.md`) replaces this with a typed contract. |
| **Archive peeking (look inside ZIPs)** | Our catalog rows aren't backed by ZIPs. The metadata fields (artist/year/label/catalog/genre, V5) cover the equivalent surface. |
| **In-BBS ANSI art editor** | Cool but very niche. Save for if there's a real "art crew" forming. |
| **Title-screen randomisation** | One step short of vibe-positive — the current banner is a known landmark. Don't randomise the door to your own house. |

---

## Recommended sequencing

If we wanted a single "make VOIDcore feel like a real BBS product"
stack of PRs, in priority order:

| # | Ticket | Theme | Why this position |
|---|---|---|---|
| 1 | [#84](../../issues/84) | User profiles | Table-stakes; everything else benefits from it |
| 2 | [#85](../../issues/85) | What's new since last call | Single biggest UX win, low cost |
| 3 | [#86](../../issues/86) | Who's online | Uses data that already exists; massive vibe payoff |
| 4 | [#87](../../issues/87) | Activity feed | Appends to the awareness theme |
| 5 | [#88](../../issues/88) | Reactions | Tiny, ubiquitous, addictive |
| 6 | [#89](../../issues/89) | Achievements + sysop notes | Pair naturally; one PR |
| 7 | [#90](../../issues/90) | Full-text search | The moment our content count exceeds memory's grep-ability |
| 8 | [#91](../../issues/91) | Watch list | Quiet mutuality, fits the small-community vibe |
| 9 | [#92](../../issues/92) | Audit log + moderation | Sysop depth, ahead of when actually needed |
| 10 | [#93](../../issues/93) | Atmosphere bundle | Bundle of small atmospheric items, one PR |

Ten tickets. None touch authenticity. All lean into the existing
architecture (typed events, JSONB, Postgres FTS, WS push). The
shortlist takes VOIDcore from "well-built BBS thin slice" to
"BBS people want to log into daily."

---

## Cross-references

- `SPEC.md` — base v1 spec
- `SPEC-doors.md` — door protocol (independent of this work)
- `DECISIONS.md` ADR-019/020 (doors), ADR-021 (admin CLI), ADR-022
  (Matrix bridge) — adjacent forward-looking decisions; this analysis
  doesn't supersede or amend any of them
- `ROADMAP.md` — v1/v2 product trajectory; the punch list above slots
  into a "v1.5 BBS-product polish" milestone between the door work
  (#46–49) and the v2 dynamic UI runtime
