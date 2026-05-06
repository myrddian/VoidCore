# VOIDcore — Screen Navigation Specification

**Version:** 1.0
**Status:** Draft for implementation (v1.4 PR-B)
**Companion to:** `SPEC-screens.md` (Screen abstraction)
**Decision referenced:** ADR-026 (stack-based navigation)

> The model the BBS uses to move users between screens. A simple
> per-session **stack of phases** with `push` / `pop` semantics. The
> top of the stack is the active screen; pushing puts a new screen on
> top and runs its `onEnter`; popping returns control to the previous
> screen.
>
> This document is the concrete reference for how navigation works.
> Every Screen author and every router implementer should be able to
> answer "what happens when I push X from here?" by reading this.

---

## 1. Concept

Each authenticated session has a **navigation stack**. The screen on
top of the stack is the active screen. Input events route to it.
Calling `push(Phase)` adds a new screen on top; the previous one
sleeps. Calling `pop()` removes the top; the screen that was beneath
it resumes — and its `onEnter` re-fires so it can re-paint with
fresh state.

```
                    [ active screen — receives input ]
                  ┌────────────────────────────────────┐
   stack top  →  │ THREAD_VIEW         (thread #42)   │
                  ├────────────────────────────────────┤
                  │ THREADS_LIST        (base #2)      │  ← sleeping
                  ├────────────────────────────────────┤
                  │ BASES_LIST                          │  ← sleeping
                  ├────────────────────────────────────┤
   stack root →  │ MENU                                │  ← root
                  └────────────────────────────────────┘

   pop() ─→ THREAD_VIEW leaves; THREADS_LIST.onEnter re-fires
   pop() ─→ THREADS_LIST leaves; BASES_LIST.onEnter re-fires
   pop() ─→ BASES_LIST leaves; MENU.onEnter re-fires
   pop() ─→ MENU leaves; stack empties → root-guard fires (logout)
```

Three operations cover the whole model: **push**, **pop**, and
**replaceTop** (used by pre-auth flows that don't really stack).

---

## 2. Why a stack

Before stack-based navigation, every screen-to-screen transition was
a direct paint call (`legacyShowBulletinsList(session)`). That meant:

- Every Screen had to know which other Screens existed and how to
  reach them
- Returning to the previous screen required the calling screen to
  remember where it came from (or the destination to remember where
  to send `[Q]`-back to)
- ScreenRouter accumulated dozens of `legacyShow*` bridge methods —
  one per cross-screen navigation point
- "Back" and "forward" were ad-hoc — each screen invented its own
  Esc behaviour

The stack model fixes all of these:

- A screen pushes the next phase by name; it doesn't need to know
  anything about its peers
- "Back" is `pop()` — uniform across the BBS
- ScreenRouter shrinks dramatically as `legacyShow*` bridges go away
- The *call graph* between screens becomes implicit (the stack is
  the call graph); no need to wire it up in code

---

## 3. The `Navigator` interface

```java
package io.aeyer.voidcore.ws.flow.screen;

public interface Navigator {
    /** Push a phase onto the stack and dispatch its onEnter. */
    void push(VoidCoreSession session, Phase phase);

    /**
     * Pop the top of the stack. If something remains underneath, that
     * screen's onEnter re-fires. If the stack would become empty, the
     * root-guard for the popping screen fires (typically logout).
     */
    void pop(VoidCoreSession session);

    /**
     * Replace the top of the stack without dispatching any onEnter.
     * Used by the pre-auth flow (login → password → menu) where the
     * linear state machine doesn't want stack semantics.
     */
    void replaceTop(VoidCoreSession session, Phase phase);

    /** Peek the top of the stack, or null if no stack yet. */
    Phase currentPhase(VoidCoreSession session);
}
```

`ScreenRouter` implements `Navigator`. Screens never see the
implementation directly — they call through `BbsContext`:

```java
public record BbsContext(...) {
    public void push(Phase phase)      { nav().push(session, phase); }
    public void pop()                  { nav().pop(session); }
    public void replaceTop(Phase phase){ nav().replaceTop(session, phase); }
    private Navigator nav()            { return (Navigator) router; }
    // ...
}
```

A Screen wants to navigate? It calls `ctx.push(Phase.X)` or
`ctx.pop()`. Nothing else.

---

## 4. Operations

### 4.1 `push(Phase)`

Pushes the given phase onto the session's stack and dispatches the
target Screen's `onEnter`. The previous screen is left intact on the
stack — when control returns via `pop()`, it resumes from where it
was.

**Semantics:**

1. `stack.push(phase)`
2. Update `phases.get(session.id())` → new SessionState with this phase
3. Look up `Screen` for this phase from the registry
4. Call `screen.onEnter(makeContext(session))` if the screen exists

**Example:** main menu → announcements list

```java
// MenuScreen.onKey when user presses [B]
ctx.push(Phase.BULLETINS_LIST);
// → BulletinsListScreen.onEnter fires; paints the list
// → user is now on BULLETINS_LIST; MENU is sleeping below
```

### 4.2 `pop()`

Removes the top of the stack. The screen beneath becomes active and
its `onEnter` re-fires (so it can re-paint — its state may have
changed while it was sleeping).

**Semantics:**

1. If stack is empty: defensive no-op
2. `Phase leaving = stack.pop()`
3. If stack is now empty: **root-guard** fires (see §5)
4. Otherwise: peek new top, update phases map, call new top's `onEnter`

**Example:** thread view → back to threads list

```java
// ThreadViewScreen.onKey when user presses [Q]
ctx.pop();
// → THREAD_VIEW leaves the stack
// → THREADS_LIST.onEnter re-fires; re-paints with any new replies
```

### 4.3 `replaceTop(Phase)`

Replaces the top of the stack without firing any `onEnter`. The
caller is expected to handle painting separately. Used exclusively
by **pre-auth flows** — see §6.

**Semantics:**

1. If stack non-empty: pop top
2. Push new phase
3. Update phases map (no onEnter dispatch)

`replaceTop` is deliberately quiet — it's a state machine primitive,
not a navigation primitive. Most Screens should never call it.

### 4.4 `currentPhase()`

Peek at the top of the stack. Returns `null` if no stack yet (i.e.
session is pre-auth or otherwise outside the stack model).

---

## 5. Root-guard

When `pop()` would empty the stack, the **root-guard** fires. The
default behaviour: trigger `onAuthLogout(session)`, which paints
the goodbye screen and closes the connection.

Why: the user reached the root of the navigation hierarchy
(typically the main menu) and pressed back. There's nowhere to go;
the natural interpretation is "I'm done, log me out."

**Other plausible root-guard behaviours** (deferred to v1.6+):

- **Confirm-and-logout** — render a "Are you sure? [Y/N]" prompt
  before tearing down the session
- **Cycle to a different root** — pop the menu, push a different
  one (e.g. an "are you sure you want to leave VOIDcore?"
  splash with options)
- **Always re-push the same root** — make the root unkillable
  (some BBSes work this way — main menu is the floor)

For v1.4 the root-guard is hardcoded to logout. The pluggable
form lands when there's a real second use case.

---

## 6. Pre-auth exception

**Login and register screens deliberately do not participate in
the stack.** They're a linear state machine that runs *before*
stack semantics begin:

```
LOGIN_HANDLE  →  LOGIN_PASSWORD  →  (auth check)  →  MENU
                                        │
                                        ↓ on user types "new"
                       REGISTER_HANDLE → REGISTER_PASSWORD →
                       REGISTER_LOCATION → ... → MENU
```

This linear sequence uses `replaceTop(Phase)` to advance — no
`onEnter` dispatch, no stack history. The pre-auth flow knows
exactly which step comes next; there's no concept of "going back"
beyond the existing per-screen Esc handler.

**When does the stack become real?** At authentication success.
`authSucceeded` calls `Navigator.resetStack(session, Phase.MENU)`,
which seeds the stack with the main menu. From this point onward,
all navigation is push/pop.

A consequence: from the user's perspective, **back from the main
menu = logout** (root-guard). They can't go "back to login" by
pressing Esc on the menu — that pre-auth state was never on the
stack.

### Why pre-auth is special

Three reasons:

1. **No "back" makes sense** during login. Pressing Esc on the
   password prompt should drop back to the handle prompt — that's
   intra-flow navigation, not stack navigation.
2. **Auth state is destructive.** Once you've passed the password
   step, the password screen has no business resuming. The linear
   state machine prevents accidental backtracks into auth state.
3. **The shape changes** at auth success. Before: linear,
   deterministic, single-purpose. After: free-form navigation among
   30+ screens. Treating both as the same primitive would force
   compromises on both sides.

---

## 7. Lifecycle

### 7.1 Connect

`onConnect` does not touch the stack. The session enters pre-auth
flow at `LOGIN_HANDLE`.

### 7.2 Auth success

`authSucceeded` calls:

```java
navigator.resetStack(session, Phase.MENU);
// followed by paint of the menu
```

The stack is now `[MENU]`. Every future `push` adds; every `pop`
returns; popping past MENU triggers logout.

### 7.3 Resume after disconnect

`auth.resume` rebuilds the user's session and jumps directly to
their `current_screen` (per SPEC §3 / `restoreFromCurrentScreen`).
The stack is reset to `[<restored_phase>]` — **the resume erases
any prior navigation history.** If the user was deep in a thread
when their connection dropped, they reconnect at `THREAD_VIEW`
with `[THREAD_VIEW]` as the only thing on the stack; pressing
`[Q]` triggers logout (root-guard).

This is intentional: navigation history is not a persistent
concept in v1.4. The user resumes at *a screen*, not in the middle
of a navigation flow. (Future versions could persist the stack;
out of scope here.)

### 7.4 Logout

Triggered by:
- User presses `[G]` from menu (explicit)
- Root-guard fires after `pop()` empties the stack
- Heartbeat/presence detects the connection has gone away

Logout flow:
1. `onAuthLogout(session)` is called
2. Paint the goodbye screen (`Phase.GOODBYE`)
3. Invalidate the DB session
4. Close the WS with `CloseStatus.NORMAL("goodbye")`
5. Stack is discarded along with the rest of the session state

### 7.5 Disconnect (transport-level, no logout)

If the WS drops without a clean logout (network blip, tab close):
- `onDisconnect(session)` runs
- Stack is forgotten (in-memory only)
- Next time the user reconnects, `auth.resume` rebuilds it from
  `current_screen` — see §7.3

The stack does not survive WS disconnects; that's deliberate. v1.4
trades navigation persistence for simplicity. The persisted slot
in `sessions.current_screen` is the resume target, not the stack
contents.

---

## 8. Examples

### 8.1 Browse a thread, post a reply, return

```
[start]                 stack: [MENU]
press [M]               push(BASES_LIST)        stack: [MENU, BASES_LIST]
pick "general"          push(THREADS_LIST)      stack: [MENU, BASES_LIST, THREADS_LIST]
pick thread "favs"      push(THREAD_VIEW)       stack: [MENU, BASES_LIST, THREADS_LIST, THREAD_VIEW]
press [R]eply           push(COMPOSE_POST)      stack: [..., THREAD_VIEW, COMPOSE_POST]
[type body in editor + :wq]
                        pop()                   stack: [..., THREAD_VIEW]
                        ↓ THREAD_VIEW.onEnter re-fires; re-paints with the new reply visible
press [Q]               pop()                   stack: [..., THREADS_LIST]
press [Q]               pop()                   stack: [MENU, BASES_LIST]
press [Q]               pop()                   stack: [MENU]
press [Q]               pop()                   → root-guard → logout
```

### 8.2 Sysop release edit walk

```
press [S] (sysop)       push(SYSOP_MENU)         stack: [MENU, SYSOP_MENU]
press [F]iles           push(SYSOP_RELEASES)     stack: [..., SYSOP_RELEASES]
pick release 1          push(SYSOP_RELEASE_EDIT) stack: [..., SYSOP_RELEASE_EDIT]
press [E]dit            (no push — internal VIEW → EDIT_MENU transition)
press [Y]ear            (no push — internal EDIT_MENU → EDITING_FIELD transition)
[type 2024 + Enter]     (field.commit; back to EDIT_MENU; year=2024 visible)
press [A]rtist          (no push)
[type SYSOP + Enter]    (field.commit; back to EDIT_MENU)
press [Q]               pop()                    stack: [..., SYSOP_RELEASES]
press [Q]               pop()                    stack: [..., SYSOP_MENU]
press [Q]               pop()                    stack: [MENU]
```

The MenuFormApp framework owns the VIEW / EDIT_MENU / EDITING_FIELD
state machine internally — the navigator stack stays at one entry
(`SYSOP_RELEASE_EDIT`) regardless of which field the user is editing.
Old per-field phases (`SYSOP_RELEASE_EDIT_TITLE`, etc.) are gone; the
form is a single ScreenApp.

### 8.3 Mention popup mid-deep-navigation

```
[user is in a thread]   stack: [MENU, BASES_LIST, THREADS_LIST, THREAD_VIEW]
[notification arrives]  notification region updates (no stack change)
[user presses Esc]      pop()
                        ↓ THREAD_VIEW leaves the stack
                        ↓ THREADS_LIST.onEnter re-fires
```

Notifications are an overlay on the active screen, not a screen of
their own. They don't push or pop.

---

## 9. Mapping legacy bridges to push/pop

The bulk of v1.4 PR-B's mechanical work is converting `legacyShow*`
calls to `ctx.push(Phase.X)` calls. The conversion is one-to-one
in most cases:

| Legacy | Stack equivalent |
|---|---|
| `legacyShowMainMenu(session)` | usually `ctx.pop()` until stack reaches `MENU` — but in practice this is *post-auth seed*, handled by `resetStack` |
| `legacyShowBulletinsList(session)` | `ctx.push(Phase.BULLETINS_LIST)` |
| `legacyShowFilesList(session)` | `ctx.push(Phase.RELEASES_LIST)` |
| `legacyShowChat(session)` | `ctx.push(Phase.CHAT)` |
| `legacyShowSysopMenu(session)` | `ctx.push(Phase.SYSOP_MENU)` |
| ... (≈25 more) | `ctx.push(Phase.<X>)` |
| `legacyShowBulletinView(session, list, b)` | set `selectedBulletinId` on session, `ctx.push(Phase.BULLETINS_VIEW)` — the screen reads its target from session state |
| `legacyShowSysopFileEditMenu(session, fid)` | set `selectedSysopId`, `ctx.push(Phase.SYSOP_RELEASE_EDIT)` |
| `legacyShowGoodbye(session)` | `onAuthLogout(session)` directly — goodbye is a terminal state, not a normal push target |
| Returns to previous screen (e.g. `[Q]` back) | `ctx.pop()` |

Once every legacy bridge has its `ctx.push` / `ctx.pop` equivalent
in place, the bridges are deleted from ScreenRouter — that's the
real shrinkage.

---

## 10. What this design does NOT do

Recorded so future work doesn't drift:

- **No "push with arguments."** Push just takes a `Phase`; per-screen
  state passed in goes through session-level fields (e.g.
  `session.setSelectedSysopId(fid)` before `ctx.push(...)`). This
  matches the existing pattern (`bulletinsCache`, `pendingHandle`,
  `currentNetmailId`, etc.). Adding typed args is a possible v1.6
  improvement but not necessary now.
- **No async push (forward note).** Push is synchronous: it
  dispatches `onEnter` inline and returns when the new screen is
  painted. Long-running work (e.g. a slow DB query in `onEnter`)
  blocks the WS handler thread for that session. At v1.4 BBS
  scale (a few hundred users at most, virtual threads under each
  WS) this is acceptable.

  **The forward direction** — captured so it's not re-derived
  cold when a screen actually starts hurting:

  - The natural async surface is *per-screen, opt-in*. A Screen
    with a slow paint declares it (e.g. by returning a
    placeholder `LayoutTree` from `onEnter` and signalling the
    framework to call back when ready). Screens that are fast —
    most of them — stay synchronous and need no opt-in.
  - The framework's contribution is **a "still working" notify
    channel**: while the slow `onEnter` runs in the background,
    the user sees a `Frames.notify` ("loading…", "still here…")
    rather than dead silence on a frozen screen. Without that
    affordance, async makes the BBS feel broken; with it, async
    feels intentional.
  - Open question for the eventual implementation: does the
    framework own the notify cadence (heartbeat every N seconds
    while a screen's async work is pending), or does each screen
    push its own progress? Probably the former for the common
    case + an optional per-screen override for fancier UX. Real
    answer waits for the first screen that actually needs it.

  Not implemented in v1.4. Recorded so the conversation, when
  a slow screen surfaces, starts at the right place rather than
  "should we even support async?"
- **No "screen result" return value.** When a child screen pops, it
  doesn't return a value to the parent; the parent simply re-paints
  via `onEnter`. State changes that the child made (e.g. wrote a
  new reply to the database) are visible to the parent's re-paint
  via the database / session-level fields.
- **No multiple concurrent stacks per session.** One stack per WS
  connection. (Multiple tabs = multiple WS connections = multiple
  stacks; that already works.)
- **No persistent navigation history across reconnects.** The stack
  is in-memory; WS drop forgets it. The persisted slot in
  `sessions.current_screen` is the *single* phase to resume at,
  not a stack.

---

## 11. Migration plan

The Navigator infrastructure is already in place (v1.4 PR-B step 4).
Subsequent commits convert screens to use `ctx.push` / `ctx.pop`,
one screen group at a time:

1. **Boot flow** — `authSucceeded` calls `resetStack(session, MENU)` so the stack is real after login
2. **Menu** — `MenuScreen` letters call `ctx.push(...)`; remove corresponding legacy bridges
3. **Announcements** — `[B]` from menu pushes `BULLETINS_LIST`; `BulletinsListScreen` digit pushes `BULLETINS_VIEW`; `[Q]` from view pops to list; `[Q]` from list pops to menu
4. **Files** — same pattern as Announcements
5. **Chat / Oneliners** — push from menu; pop on Esc
6. **VoidMail** — inbox / read / compose all use push/pop; compose's three sub-steps push linearly, pop returns to inbox
7. **Forum** — bases → threads → thread view → compose; deep stack
8. **Sysop tools** — sysop menu → users / announcements / files / broadcast; file edit pushes per-field screens
9. **Cleanup** — delete every now-unused `legacyShow*` bridge from ScreenRouter

Each commit converts one screen group, removes its corresponding
bridges, ScreenRouter shrinks. Build is green at every commit
(legacy bridges and stack-based push coexist during migration).

---

## 13. Messaging (cross-session change notification)

> Decision recorded in **ADR-027**. v1.4 ships a topic-based,
> payload-less, in-process pub/sub bus. The bus signals "this
> topic changed; re-read"; the DB carries the data.

The Navigator stack model gives the BBS a clean answer to "what is
this session looking at right now?" — the top of its stack. The
**MessageBus** answers the complementary question: "when something
on the screen changes elsewhere, how do other viewers find out?"

### 13.1 Concept

Every screen that benefits from live updates is **DB-backed** —
oneliners table, chat_messages table, files table, threads/posts
tables. Cross-session messaging therefore isn't *delivering data*;
it's saying *"the data you're looking at changed; re-fetch."*
The DB is the source of truth. The bus is the refresh-trigger.

```
[Writer]               [MessageBus]                [Subscriber]
   │                        │                           │
   │  insert into oneliners │                           │
   ├───────────────────────►│                           │
   │                        │                           │
   │  ctx.publish(          │                           │
   │   "oneliners")         │                           │
   ├───────────────────────►│                           │
   │                        │  notify(topic)            │
   │                        ├──────────────────────────►│
   │                        │                           │  re-read DB
   │                        │                           │  re-paint
   │                        │                           │
```

### 13.2 The `MessageBus` interface

```java
public interface MessageBus {
    void subscribe(VoidCoreSession session, String topic);
    void unsubscribe(VoidCoreSession session, String topic);
    void unsubscribeAll(VoidCoreSession session);   // on disconnect
    void notify(String topic);                   // no payload
}
```

Implementation: in-process `Map<String, Set<VoidCoreSession>>`. At
delivery time, stale `!isOpen()` sessions are cleaned up.

### 13.3 Lifecycle integration with the stack

Subscriptions are wired automatically by the router around every
push / pop:

| Event | Action |
|---|---|
| `Navigator.push(phase)` | Dispatch screen's `onEnter`, then subscribe session to `screen.topics(ctx)` |
| `Navigator.pop()` | Unsubscribe leaving screen's topics, dispatch new top's `onEnter` |
| `Navigator.replaceTop(phase)` | Unsubscribe old top's topics, subscribe new top's topics, no `onEnter` |
| `onDisconnect(session)` | `bus.unsubscribeAll(session)` |
| `authSucceeded` (stack seed) | Subscribe to `MENU`'s topics |

A screen's "I am listening" lifetime is **exactly** its time on
top of the stack. Push and pop are subscribe and unsubscribe in
disguise.

### 13.4 Screen-side hooks

`Screen` gains two optional methods:

```java
public interface Screen {
    /** Topics this screen subscribes to while it's the active top of stack. */
    default List<String> topics(BbsContext ctx) { return List.of(); }

    /** Called when a subscribed topic publishes. Default: re-fire onEnter. */
    default Transition onEvent(BbsContext ctx, String topic) {
        return onEnter(ctx);
    }

    // existing onEnter / onKey / onLine / onCancel unchanged
}
```

`onEvent` defaults to a full repaint via `onEnter`. A screen
that wants finer-grained behaviour (e.g. chat appending one new
line instead of re-painting all 50) overrides `onEvent` to handle
the topic locally.

### 13.5 Writer-side: `ctx.publish(topic)`

```java
// OnelinersScreen.onLine, after a successful insert:
ctx.publish("oneliners");
// no iteration, no filter, no broadcast loop
```

`BbsContext` exposes `publish(String topic)` as a one-liner that
delegates to the bus. Writers never see subscriber lists.

### 13.6 Topic catalogue (v1.4)

| Topic | Published by | Subscribed by |
|---|---|---|
| `oneliners` | OnelinersScreen on insert | OnelinersScreen |
| `chat:general` | ChatRoomScreen on submit | ChatRoomScreen |
| `documents` | ReleaseViewScreen on download counter increment | ReleasesListScreen, ReleaseViewScreen |
| `threads-list:base/{id}` | Forum on new thread / new reply | ThreadsListScreen viewing that base |
| `thread:{id}` | Forum on reply within thread | ThreadViewScreen viewing that thread |
| `presence` | PresenceService on join/leave | (banner overlay; not screen-bound) |

The naming convention: lowercase, slash-separated where there's a
hierarchy, colon-separated for namespaces. Topic strings are not
parsed by the bus — they're opaque keys.

### 13.7 Why no payloads

Captured in ADR-027 but worth restating:

- **Idempotent.** A missed notification is one repaint stale.
  Next push or next event makes it fresh.
- **No replay logic.** The DB is the truth; events are ephemeral.
- **No event types to design.** Topic strings only. Adding typed
  payloads later is per-topic, backward-compatible with itself.
- **Trivial implementation.** ~50 lines for the in-process bus.

### 13.8 Targeted notifications (deferred)

Mentions (`@handle` → toast for the mentioned user) and sysop
broadcasts (force-push to every authenticated session) are
**different semantics** from topic invalidation:

- They're addressed to *specific users*, not subscribers of a topic
- They typically carry a payload (the message text)
- They want delivery confirmation more than topic invalidation does

v1.4 keeps these in their existing imperative form
(`ScreenRouter.notifyMentions`, sysop-broadcast session walk).
The bus does not handle them.

**Forward path** (recorded for when a real second use case
arrives): per-user topics. Each authenticated session subscribes
to `user:{id}` on login. Mention parsing publishes to
`user:{mentioned_id}` with a payload (the mention or a generic
"you have something new" trigger). The same `MessageBus`
implementation handles both classes; the difference is "topic
identifies a thing being watched" vs "topic identifies a user."
Both fit the existing wire shape.

**Not implemented in v1.4.** Recorded so the v1.6 conversation
starts at the right place.

### 13.9 Implementation phasing within PR-B

The bus replaces the imperative broadcast loops. Sequenced:

1. Add `MessageBus` interface + in-process implementation
2. Wire subscribe/unsubscribe into Navigator's push/pop/replaceTop
3. `BbsContext.publish(String topic)` helper
4. Add `Screen.topics()` + `Screen.onEvent()` defaults
5. Convert each `broadcast*` to a `notify(topic)`:
   - `broadcastOnelinerWall` → `notify("oneliners")`
   - `broadcastChatRoom` → `notify("chat:general")`
   - `broadcastFileListUpdate` → `notify("documents")`
   - `broadcastThreadsListUpdate(baseId)` → `notify("threads-list:base/" + baseId)`
   - `broadcastThreadView(threadId)` → `notify("thread:" + threadId)`
6. Each affected Screen overrides `topics()` to subscribe
7. Delete the now-unused `broadcast*` methods from ScreenRouter

After this lands, ScreenRouter loses ~120 lines of broadcast
machinery and gains zero — the bus is in its own
`MessageBus` component.

---

## 14. Glossary

- **Navigator.** The interface that exposes stack operations
  (`push`/`pop`/`replaceTop`). `ScreenRouter` implements it; screens
  call it via `BbsContext`.
- **Stack.** The ordered list of pushed phases for one session. Top
  of the stack is the active screen.
- **Push.** Add a phase on top; dispatch its `onEnter`; previous top
  sleeps.
- **Pop.** Remove the top; previous screen resumes via re-fire of
  its `onEnter`.
- **Replace-top.** Pre-auth-only operation: swap the top phase
  without dispatching `onEnter`.
- **Root-guard.** Behaviour invoked when `pop()` would empty the
  stack. v1.4 default: logout.
- **Pre-auth flow.** The login + register state machine; runs
  before the stack is real. Uses `replaceTop` to advance.
- **Resume.** On reconnect, the user lands on a single phase from
  `sessions.current_screen`; the stack is reset to that one entry.
  Prior navigation history is forgotten.
- **MessageBus.** The cross-session change-notification primitive.
  Topic-based; payload-less; in-process for v1.4.
- **Topic.** A string key identifying a thing being watched
  (e.g. `oneliners`, `thread:42`). Subscribers are sessions; the
  bus delivers `notify(topic)` to each open subscriber.
- **Topic invalidation.** The semantic the bus carries: "the data
  this topic represents has changed; subscribers should re-read."
  The DB holds the data; the bus only triggers refresh.
- **Targeted notification.** Addressed-to-a-specific-user
  semantic (mentions, sysop broadcasts). NOT covered by the
  v1.4 bus; deferred to per-user topics in a later version.
