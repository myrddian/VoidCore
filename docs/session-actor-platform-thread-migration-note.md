# Session Actor Platform-Thread Migration Note

**Date:** 2026-05-07
**Status:** Pre-change design note
**Scope:** session actor execution model only

> This note captures the expected migration shape if VOIDcore moves its
> per-session actors from Loom virtual threads to dedicated platform
> threads. It exists to pin down the invariants before any code changes
> land, especially now that the extension runtime has introduced a new
> thread-affinity concern around embedded GraalJS execution.

---

## Why This Note Exists

VOIDcore currently uses:

- Spring request handling on virtual threads
- one virtual-thread `SessionActor` per live session
- actor-serialised access to all session-owned state

That model works well for the core BBS flow, but it becomes more
questionable once embedded runtimes such as GraalJS enter the picture.
Graal's current warnings are not about correctness of the BBS itself;
they are warning that some guest-language/runtime behaviour on Loom is
still considered experimental.

The temptation is to "hand off" Graal execution to another thread pool.
That is not obviously safe in VOIDcore because session and navigation
semantics are intentionally single-threaded. A cross-thread handoff that
then calls back into session APIs can create subtle re-entrancy and
wait-graph risks.

This note explores a simpler alternative:

- keep the actor model
- keep single-threaded session ownership
- change only the worker type behind `SessionActor`
- use dedicated platform threads instead of virtual threads

---

## Current Invariants To Preserve

Any migration away from Loom must preserve these properties:

1. Exactly one execution lane owns mutable session state at a time.
2. `SessionProxy` remains the public boundary for all session access.
3. Reads and writes from outside the actor still route through
   `SessionActor.submit(...)`.
4. Bus notifications remain fire-and-forget through
   `SessionActor.enqueueAsync(...)`.
5. Re-entrant calls from the actor thread to itself still short-circuit
   directly instead of deadlocking on the queue.
6. WebSocket writes for a given session remain serialised through the
   session actor.
7. The rest of the app should not need to know whether the actor worker
   is virtual or platform-backed.

If those invariants hold, the concurrency contract seen by screens,
services, routing, and heartbeats remains the same.

---

## Proposed Change

Replace the worker creation inside
[`SessionActor.java`](../app/src/main/java/io/aeyer/voidcore/ws/session/SessionActor.java)
from:

```java
Thread.ofVirtual().name("session-actor-" + core.id()).start(this::loop);
```

to a dedicated platform-thread worker per session actor.

Conceptually:

```java
Thread worker = Thread.ofPlatform()
        .name("session-actor-" + core.id())
        .start(this::loop);
```

or, if Ciotola becomes the backing implementation:

- preserve `submit(...)` and `enqueueAsync(...)` semantics
- preserve re-entrant short-circuit semantics
- preserve one-owner-per-session state discipline

The intended migration is behavioural substitution, not a redesign of
the session contract.

---

## Why This Is Attractive

Compared with adding a separate Graal handoff layer, moving session
actors to platform threads has a narrower conceptual blast radius:

- the session still owns its state in one serial lane
- navigation still happens in that same lane
- custom screens do not need a second concurrency model just to run
- Graal thread-affinity concerns become easier to reason about because
  the hosting lane is no longer Loom-based

This may be less work than building a second actor boundary just for the
extension runtime and then carefully proving that its callbacks cannot
deadlock against session ownership.

---

## Files Expected To Change

### Primary

- [`SessionActor.java`](../app/src/main/java/io/aeyer/voidcore/ws/session/SessionActor.java)
  Replace worker creation and update comments that currently describe the
  actor as virtual-thread-backed.

### Likely small follow-ups

- [`VoidCoreSession.java`](../app/src/main/java/io/aeyer/voidcore/ws/VoidCoreSession.java)
  Update interface-level concurrency comments.
- [`SessionProxy.java`](../app/src/main/java/io/aeyer/voidcore/ws/session/SessionProxy.java)
  Update comments that talk about virtual-thread context switches.
- [`SessionRegistry.java`](../app/src/main/java/io/aeyer/voidcore/ws/SessionRegistry.java)
  Mostly comment/lifecycle wording; logic should remain the same.
- [`docs/DECISIONS.md`](DECISIONS.md)
  ADR-002 would need amendment or a superseding ADR if the change is
  accepted project-wide.

### Should not need behavioural changes if the contract is preserved

- screens
- `ScreenRouter`
- `BbsContext`
- heartbeat scheduling logic
- message bus topic invalidation
- auth / reconnect semantics

---

## Risks And Tradeoffs

### 1. Higher thread cost per live session

The current Loom model makes one-actor-per-session very cheap. Moving to
platform threads raises:

- per-session memory cost
- OS scheduling overhead
- upper bound pressure as concurrent sessions grow

For a BBS-scale system this may still be acceptable, but it is a real
tradeoff.

### 2. Existing "virtual threads everywhere" decision becomes narrower

ADR-002 currently states that blocking request/connection handling on
virtual threads is the concurrency story. If session actors move to
platform threads, the project becomes a hybrid model:

- Spring/Tomcat request handling on Loom
- session-state ownership on platform threads

That is valid, but it should be documented explicitly.

### 3. Graal warning elimination is not the same as proof of safety

Moving session actors to platform threads may remove the immediate Loom
conflict for guest-language execution, but it does not automatically
prove that every future extension callback is safe. It only puts the
execution model on more predictable ground.

---

## Risks Explicitly Avoided By This Approach

This migration intentionally avoids:

- a shared Graal worker pool that blocks request/session threads waiting
  for callbacks to finish
- a second actor model beside the session actor just for script runtime
- synchronous cross-thread navigation handoffs that can re-enter custom
  screens mid-callback

Those designs may still be possible, but they are harder to reason
about than "the session still owns execution; only the worker type
changes."

---

## Non-Goals

This migration note does **not** propose:

- replacing Spring WebSocket with a selector-driven NIO server
- changing the external WebSocket or screen protocol
- changing queue semantics in `SessionActor`
- changing the reconnect/detach model in `SessionRegistry`
- changing screen APIs
- solving every possible extension-runtime design issue

It is specifically about the session actor's backing thread type.

---

## Validation Plan If The Migration Proceeds

Before calling the migration complete, re-verify:

1. login / logout flows
2. reconnect / detach / resume semantics
3. heartbeat ping / pong serialisation
4. bus topic invalidation delivery
5. custom screen navigation push/pop
6. sysop workflows with multi-step state
7. extension runtime entry after login

Focus especially on places where:

- a screen callback reads session state while already inside actor-owned
  execution
- a bus event causes re-entry into screen render paths
- a WebSocket write races against another outbound action

---

## Decision Gate

Before making the code change, answer this:

> Is the project more likely to be constrained by session-count scaling,
> or by runtime predictability and thread-affinity correctness for the
> extension boundary?

If runtime predictability wins, platform-thread actors are a credible
next step.

If session-count scaling still matters more, a second pass should first
explore whether the Graal runtime can be isolated without changing the
session actor model.
