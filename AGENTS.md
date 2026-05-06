# AGENTS.md — project rules for any agent working on VOIDcore

> **Audience:** AI coding agents and contributors editing the core
> engine. These are guard-rails for working on the codebase, not
> user-facing documentation. If you are running VOIDcore as an
> operator, see [README.md](README.md) instead.

This file holds rules that override default behaviour. Read it at the
start of every session. The rules here are short on purpose; the
reasoning lives in `DECISIONS.md` (ADRs) and the relevant SPEC files.

If a rule here conflicts with a default workflow (TDD, "always do X"),
the rule here wins.

---

## Migration discipline

The v1.4 refactor is splitting `ScreenRouter` (a 3000-line god object)
into Screens, Navigator, and BbsServices. The migration is incremental
— some code still goes through legacy paths. There is exactly one rule
that keeps the migration honest:

> **STOP before adding any new `legacyX` method on ScreenRouter, any
> new cast through `ctx.legacyRouter()`, or any new `(ScreenRouter)
> ctx.router()` cast in a Screen.**

Existing `legacyX` bridges are tracked tech debt; they have a path to
deletion as their owning screen migrates. **A new bridge is a
regression** — it cements another point of coupling that has to be
torn out later.

When the temptation arises:

1. Stop. Don't add the bridge.
2. Surface why you wanted to. Usually one of:
   - **The screen wants state that doesn't belong to it.** (e.g. data
     caches — those belong to a View layer, not the screen. See
     ADR-029.)
   - **The helper doesn't actually need router state.** (Move it to
     `BbsServices`. See ADR's defining `BbsServices`.)
   - **The cross-session broadcast is doing it imperatively.** (Use
     the message bus. See ADR-027.)
3. Pause and ask the human (or, if working solo, write up the
   architectural mismatch as a note before proceeding).
4. Either move the helper properly, or rethink the screen's
   responsibility, or design the new abstraction. Then proceed.

If at the end of all that the only path forward really is a new
bridge, that's a signal the architecture is missing something — write
up an ADR explaining what's missing before adding the bridge.

See ADR-028 for the full rationale.

---

## Caches and broadcasts

- Screens **must not** hold lists of data they're displaying as
  per-session state. Use a View layer (cache + bus subscription); see
  ADR-029.
- Cross-session change notification goes through the message bus as
  topic invalidation (`ctx.publish("topic")`). Don't add new
  imperative `broadcastX` loops; see ADR-027.
- `pendingHandle` and similar mid-flow form state belongs to the
  Screen owning the flow, not to a router-wide map.

---

## When in doubt

Read the relevant ADR. The DECISIONS.md is the source of truth for
"why we built it this way." If the answer isn't there, it's worth
writing one before you write code.
