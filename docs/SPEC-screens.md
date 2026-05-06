# VOIDcore — Screen Architecture Specification

**Version:** 1.0
**Status:** Draft for implementation (v1.4 milestone — internal
refactor; no protocol or behaviour change)
**Companion to:** `SPEC.md` (BBS↔client protocol, `voidcore-node-v1`)
**Decision referenced:** ADR-025 (screen abstraction + layout-as-data)

> The substrate-level refactor that lets every subsequent feature
> (doors, documents, polish) land cleanly. Splits the existing
> ScreenRouter God Object into a slim dispatcher + one class per
> screen + a layout data structure + a richer theme system.
>
> Internal-only. No new tables, no new protocol messages, no UX
> change. Equivalent behaviour, much smaller per-screen cognitive
> footprint.

---

## 1. Concept

A **screen** is a top-level renderable surface. The user is
always on exactly one screen at a time. Screens own their own:

- State machine (which sub-state am I in within this screen?)
- Input handlers (keystroke, line submit, cancel)
- Initial render
- Transitions to other screens

The current implementation has all this in one class
(`ScreenRouter.java`). This spec replaces it with one class per
screen, plus a slim router that dispatches based on phase.

Three layers:

```
┌────────────────────────────────────────────────┐
│  ScreenRouter — pure dispatcher (~150 LOC)     │
│    Phase → Screen instance                     │
└──────────────────┬─────────────────────────────┘
                   │ delegates onKey / onLine / onEnter / onCancel
                   ▼
┌────────────────────────────────────────────────┐
│  Screen — interface + ~30 implementations      │
│    LoginScreen, MenuScreen, ChatRoomsScreen, ...│
│    Each owns its state, handlers, layout       │
└──────────────────┬─────────────────────────────┘
                   │ produces LayoutTree
                   ▼
┌────────────────────────────────────────────────┐
│  LayoutRenderer — theme-aware                  │
│    Walks LayoutTree, applies active theme,     │
│    produces Frames.update payloads             │
└────────────────────────────────────────────────┘
```

---

## 2. The `Screen` interface

```java
package io.aeyer.voidcore.ws.flow.screen;

public interface Screen {
    /** The Phase this screen handles. Router uses this for dispatch. */
    Phase phase();

    /** Stable name for logging, audit, and current_screen JSONB. */
    String name();

    /** First paint after entering this screen. */
    LayoutTree onEnter(BbsContext ctx);

    /** Keystroke-mode input. */
    Transition onKey(BbsContext ctx, String key);

    /** Line-mode input. */
    Transition onLine(BbsContext ctx, String text);

    /** Esc / cancel. */
    Transition onCancel(BbsContext ctx);
}
```

### `BbsContext`

Threaded through every handler. Carries the moving parts:

```java
public record BbsContext(
    VoidCoreSession session,
    UserRow user,                  // null pre-auth
    ServiceBundle services         // injectable services
) {
    public void send(ServerMessage m) { ... }
    public void notify(String text, String level, long durationMs) { ... }
    public void persistCurrentScreen(String json) { ... }
    public void audit(String action, JsonNode payload) { ... }
    // … convenience accessors that screens commonly need
}
```

Avoids passing 8 arguments to every handler; centralises the
sysop-side service access into one bag.

### `Transition`

Tells the router what to do after a handler returns:

```java
public sealed interface Transition {
    /** Stay on this screen; optionally re-render. */
    record Stay(LayoutTree refresh) implements Transition {}

    /** Go to another screen, optionally with payload data. */
    record To(Phase next, Map<String, Object> args) implements Transition {}

    /** Back-up one screen (consults the session's screen stack). */
    record Back() implements Transition {}

    /** Close the connection (logout / goodbye). */
    record End(String reason) implements Transition {}
}
```

`Stay` with `refresh = null` is a no-op (the input was handled but
the screen content doesn't change — e.g. an invalid keystroke).

`Back` consults a small per-session screen stack maintained by the
router. Push on `To`, pop on `Back`.

> **Navigation is its own spec now.** Stack-based push/pop semantics,
> root-guard behaviour, the pre-auth exception (login/register don't
> stack), lifecycle on connect/auth/resume/logout, the
> legacy-bridge-to-`ctx.push` migration table — all live in
> **`SPEC-screen-navigation.md`** with **ADR-026** capturing the
> rationale. That document is the concrete reference for *how* the
> system navigates; this one stays focused on *what a Screen is*.

### `Phase` enum

The phase enum (currently in `ScreenRouter.java`) moves to its own
file `flow/screen/Phase.java`. Each `Screen` declares which
`Phase` it handles via `phase()`. Spring discovers all screens and
the router builds `Map<Phase, Screen>` at startup. **Compile-time
guarantee**: every `Phase` enum value has exactly one `Screen`
implementation, or startup fails.

---

## 3. The router

```java
@Component
public class ScreenRouter {

    private final Map<Phase, Screen> screens;
    private final SessionPhases phases;
    private final SessionScreenStack stack;

    public ScreenRouter(List<Screen> all, ...) {
        this.screens = all.stream()
            .collect(toUnmodifiableMap(Screen::phase, identity(),
                (a, b) -> { throw new IllegalStateException(
                    "Two screens claim phase " + a.phase() +
                    ": " + a.name() + " and " + b.name()); }));
        // sanity-check coverage
        for (Phase p : Phase.values()) {
            if (!screens.containsKey(p))
                throw new IllegalStateException(
                    "No Screen handles phase " + p);
        }
    }

    public void onKeystroke(VoidCoreSession s, ClientMessage.Keystroke req) {
        Screen screen = screens.get(phases.current(s));
        Transition t = screen.onKey(ctx(s), req.key().toUpperCase());
        applyTransition(s, t);
    }

    public void onLineSubmit(VoidCoreSession s, ClientMessage.LineInput req) {
        Screen screen = screens.get(phases.current(s));
        Transition t = screen.onLine(ctx(s), req.text());
        applyTransition(s, t);
    }

    public void onLineCancel(VoidCoreSession s) {
        Screen screen = screens.get(phases.current(s));
        Transition t = screen.onCancel(ctx(s));
        applyTransition(s, t);
    }

    private void applyTransition(VoidCoreSession s, Transition t) {
        switch (t) {
            case Transition.Stay st -> { if (st.refresh() != null) render(s, st.refresh()); }
            case Transition.To to    -> enter(s, to.next(), to.args());
            case Transition.Back b   -> enter(s, stack.pop(s), Map.of());
            case Transition.End e    -> close(s, e.reason());
        }
    }
}
```

That's the whole router. ~100 lines including imports.

---

## 4. `LayoutTree`

A small data hierarchy describing what to render. Screens build
trees in `onEnter()` (and on refresh inside transitions). The
renderer walks the tree and produces `Frames.update` calls.

### 4.1 Node types

```java
public sealed interface LayoutNode {

    /** Top-of-screen heading. */
    record Header(String text, Style style) implements LayoutNode {}

    /** Empty row for spacing. */
    record Spacer() implements LayoutNode {}

    /** Single-line label : value pair. */
    record MetaRow(String label, String value, Style style) implements LayoutNode {}

    /** A bordered box around inner content (used for NFOs, frames). */
    record FrameBox(int innerWidth, List<LayoutNode> body, Style border) implements LayoutNode {}

    /** Free-flowing text, multi-line. URLs are linkified by the renderer. */
    record Body(String text, Style style) implements LayoutNode {}

    /** A list of selectable rows; each has a label and an optional digit prefix. */
    record Menu(List<MenuItem> items) implements LayoutNode {}
    record MenuItem(String accelerator, String label, Style accelStyle, Style labelStyle) {}

    /** Footer help line — usually keystroke hints. */
    record Footer(String text, Style style) implements LayoutNode {}

    /** Input prompt at the bottom of the screen. */
    record Prompt(InputMode mode, String label, Integer maxLength,
                  String validKeys, String initial) implements LayoutNode {}
}

public record LayoutTree(List<LayoutNode> nodes) {
    public static LayoutTree of(LayoutNode... nodes) { ... }
}
```

`LayoutNode` is a sealed interface, so adding a new node type is a
compile-time impact on the renderer — totality enforced.

### 4.2 `Style`

Abstract style names — never concrete CGA colours in a screen
class:

```java
public enum Style {
    PRIMARY,   // headers, important text — usually bright_yellow
    ACCENT,    // accelerators, links — usually bright_cyan
    MUTED,     // secondary text, hints — usually grey
    BODY,      // default reading colour
    ALERT,     // warnings, errors — usually bright_red
    SUCCESS,   // success states — usually bright_green
    BORDER     // frame characters
}
```

The renderer maps `Style → ServerMessage.Span colour name` based
on the active theme. Screens never speak in CGA names.

### 4.3 Building a tree

Example — what the chat screen returns from `onEnter()`:

```java
LayoutTree.of(
    new Header("== MULTINODE CHAT ==   #general", Style.PRIMARY),
    new Spacer(),
    new Body(formatChatHistory(recentMessages), Style.BODY),
    new Spacer(),
    new Footer("[Esc] to leave", Style.MUTED),
    new Prompt(InputMode.LINE, "chat (/me action, [Esc] to leave):",
               240, null, null)
);
```

Compare with the current `ScreenRouter.showChat()` which interleaves
hardcoded colour strings (`"bright_yellow"`, `"grey"`) and direct
`Frames.update` calls. The new form is data; theming applies
later.

---

## 5. Theme model

Themes are YAML files in `app/src/main/resources/themes/`. Each
file declares palette + borders + glyphs. The theme picker (`#41`)
loads and lists them.

### 5.1 File format

```yaml
# themes/phosphor.yaml
name: "Phosphor"
description: "Classic green CRT phosphor"

palette:
  primary:  bright_green
  accent:   bright_cyan
  muted:    grey
  body:     default
  alert:    bright_red
  success:  bright_green
  border:   bright_cyan

borders:
  horizontal: "─"
  vertical:   "│"
  corner_tl:  "┌"
  corner_tr:  "┐"
  corner_bl:  "└"
  corner_br:  "┘"

glyphs:
  header_left:  "═══"
  header_right: "═══"
  bullet:       "•"
  prompt:       "▸"
  selected:     "▶"
  unselected:   " "
```

Or a minimalist ASCII theme:

```yaml
# themes/ascii.yaml
name: "ASCII"
description: "Pure ASCII; no Unicode"

palette:
  primary: bright_yellow
  accent:  bright_cyan
  muted:   grey
  body:    default
  alert:   bright_red
  success: bright_green
  border:  bright_cyan

borders:
  horizontal: "-"
  vertical:   "|"
  corner_tl:  "+"
  corner_tr:  "+"
  corner_bl:  "+"
  corner_br:  "+"

glyphs:
  header_left:  "=="
  header_right: "=="
  bullet:       "*"
  prompt:       ">"
  selected:     ">"
  unselected:   " "
```

### 5.2 Loading

- Themes loaded at startup from classpath + filesystem (`themes/*.yaml`).
- Validated: every required key present, palette values are valid
  CGA colour names, border characters are single codepoints.
- Active theme resolved per-session: user preference (existing `#41`
  flow) → fallback to default theme.

### 5.3 No hot-reload in v1.4

Adding a new theme requires a restart. This is a deliberate
non-decision — see ADR-025's "Out of scope." Hot-reload of themes
is a v2-runtime concern, not v1.

### 5.4 What themes can change

| Theme controls | Theme does NOT control |
|---|---|
| Colours (palette mapping) | Layout (what rows go where) |
| Border characters | Number / order of menu items |
| Header / bullet / prompt glyphs | Per-screen content |
| | Per-screen logic |

If a sysop wants to add a new menu item, that's a code change
(new `MenuItem` in the screen's `LayoutTree`). Themes are about
*how* things look, not *what* things exist.

---

## 6. The renderer

```java
@Component
public class LayoutRenderer {

    private final ThemeRegistry themes;

    public void render(BbsContext ctx, LayoutTree tree) {
        Theme theme = themes.activeFor(ctx.session());
        List<Row> rows = new ArrayList<>();
        int rowN = 0;
        for (LayoutNode node : tree.nodes()) {
            rowN = renderNode(node, rows, rowN, theme);
        }
        ctx.send(Frames.update("main", nextVersion(ctx), rows));
        for (LayoutNode node : tree.nodes()) {
            if (node instanceof LayoutNode.Prompt p) {
                ctx.send(toPrompt(p));
                break;
            }
        }
    }

    private int renderNode(LayoutNode node, List<Row> rows, int rowN, Theme theme) {
        return switch (node) {
            case LayoutNode.Header h -> renderHeader(h, rows, rowN, theme);
            case LayoutNode.Spacer s -> { rows.add(Frames.blank(rowN)); yield rowN + 1; }
            case LayoutNode.MetaRow m -> renderMetaRow(m, rows, rowN, theme);
            case LayoutNode.FrameBox f -> renderFrameBox(f, rows, rowN, theme);
            case LayoutNode.Body b -> renderBody(b, rows, rowN, theme);
            case LayoutNode.Menu m -> renderMenu(m, rows, rowN, theme);
            case LayoutNode.Footer f -> renderFooter(f, rows, rowN, theme);
            case LayoutNode.Prompt p -> rowN; // prompts handled separately
        };
    }
    // … per-node render methods
}
```

Sealed `LayoutNode` means the switch is exhaustive — adding a new
node type is a compile error until the renderer handles it.

The `nextVersion(ctx)` helper increments per-region version numbers
to keep the existing protocol's region-version reconciliation
working.

---

## 7. Per-screen file layout

```
app/src/main/java/io/aeyer/voidcore/ws/flow/
├── screen/
│   ├── Screen.java                  ← interface
│   ├── Phase.java                   ← enum, moved out of ScreenRouter
│   ├── Transition.java              ← sealed interface
│   ├── BbsContext.java              ← record
│   ├── LayoutNode.java              ← sealed interface
│   ├── LayoutTree.java              ← record wrapper
│   ├── Style.java                   ← enum
│   ├── LayoutRenderer.java          ← the renderer
│   ├── theme/
│   │   ├── Theme.java               ← record (palette + borders + glyphs)
│   │   ├── ThemeRegistry.java       ← loads YAMLs at startup
│   │   └── ThemeProperties.java     ← Spring config binding
│   ├── ScreenRouter.java            ← slim dispatcher
│   └── impl/
│       ├── LoginHandleScreen.java
│       ├── LoginPasswordScreen.java
│       ├── MenuScreen.java
│       ├── BulletinsListScreen.java
│       ├── BulletinViewScreen.java
│       ├── ReleasesListScreen.java
│       ├── ReleaseViewScreen.java
│       ├── OnelinersScreen.java
│       ├── ChatRoomsScreen.java
│       ├── ChatRoomScreen.java
│       ├── NetmailInboxScreen.java
│       ├── NetmailReadScreen.java
│       ├── NetmailComposeToScreen.java
│       ├── NetmailComposeSubjectScreen.java
│       ├── NetmailComposeBodyScreen.java
│       ├── InfoViewScreen.java
│       ├── SysopMenuScreen.java
│       ├── SysopUsersScreen.java
│       ├── SysopUserDetailScreen.java
│       ├── SysopBulletinsScreen.java
│       ├── SysopReleasesScreen.java
│       ├── SysopFileEditMenuScreen.java
│       ├── … (more sysop / file edit / new file screens)
│       ├── BasesListScreen.java
│       ├── ThreadsListScreen.java
│       ├── ThreadViewScreen.java
│       ├── ComposeThreadScreen.java
│       ├── ComposePostScreen.java
│       ├── RegisterScreen.java     ← merges 6 register phases via state field
│       └── GoodbyeScreen.java
```

About 30 screen classes. Each file is small, single-responsibility,
unit-testable.

---

## 8. Plugin contract

`Screen` is a **public extension point** — annotated `@Component`
in any JAR on the classpath gets discovered and registered.
Documented in `SPEC.md` as a stable API.

### 8.1 Adding a new screen (in-process)

A third party (or future-you) creates a JAR containing:

```java
@Component
public class WeatherScreen implements Screen {
    @Override public Phase phase() { return Phase.WEATHER; }
    @Override public String name() { return "weather"; }

    @Override
    public LayoutTree onEnter(BbsContext ctx) {
        WeatherReport report = fetchWeather();
        return LayoutTree.of(
            new Header("== WEATHER ==", Style.PRIMARY),
            new Spacer(),
            new Body(formatReport(report), Style.BODY),
            new Footer("[R]efresh   [Q]uit", Style.MUTED),
            new Prompt(InputMode.KEYSTROKE, "weather:", null, "RQ", null)
        );
    }
    // ... onKey, onLine, onCancel
}
```

Drop the JAR into the classpath, add `WEATHER` to the `Phase` enum
via a separate "extension phases" mechanism (TBD — see Open
Questions §11), redeploy. It's available.

The `Phase` enum extension is the one piece of friction; v1.4
keeps `Phase` as a closed enum and treats third-party screens as a
v1.6+ concern. Core screens are the v1.4 target; the *contract* is
proven now even if the *open registry* is later.

### 8.2 Sidecar plugins

For non-JVM extensions, the door protocol (ADR-019) remains
available. A sidecar door is a `Screen` from the BBS's
perspective; the door SDK already abstracts the Screen contract
via the protocol. In v1 Normal mode, that means the door renders
into the same primary `main` application surface a `ScreenApp`
would use, while the BBS minimises banner chrome and retains
ownership of global UI concerns around it.

---

## 9. Migration strategy

The refactor lands in **3 stacked PRs**, mirroring the ADR-025
steps:

### PR-A: Screen interface + extraction (no behaviour change)

- Add `Screen`, `Phase`, `Transition`, `BbsContext`, `ScreenRouter`
- Move existing `ScreenRouter.java` logic out into per-screen
  classes one at a time
- Each screen still calls `Frames.update()` directly for now
- Once all phases are extracted, delete the old monolithic file

Acceptance: every phase has a Screen implementation; existing
acceptance tests pass unchanged; bytecode-equivalent UX.

### PR-B: LayoutTree + LayoutRenderer + theme expansion

- Add `LayoutNode`, `LayoutTree`, `Style`, `LayoutRenderer`
- Add `Theme`, `ThemeRegistry`, `ThemeProperties`; convert existing
  4 themes (`#41`) to YAML files
- Migrate screens one at a time to return `LayoutTree` from
  `onEnter()`
- Renderer becomes the single point that calls `Frames.update`
- Audit: no `bright_yellow`, `grey`, etc. literals in any screen
  class — only `Style` enum values

Acceptance: theme switching still works; all screens render in
all themes; existing UX unchanged.

### PR-C: Plugin contract documentation + first plugin

- Add a small public-API JavaDoc surface: `Screen`, `BbsContext`,
  `LayoutTree`, `Theme`
- Update `SPEC.md` to document the extension point
- (Optional) Add an example plugin screen to demonstrate

Acceptance: contract is publicly written; future doors / documents
work has a stable target.

---

## 10. v2 forward-compatibility

`LayoutTree` is structurally identical to v2's `screen.define`
payload (ADR-017). The mapping:

| `LayoutTree` (v1.4) | `screen.define` (v2) |
|---|---|
| `Header { text, style }` | `region: { type: "text-cell", content: ... }` |
| `Menu { items }` | `region: { type: "list", items: ... }` |
| `Prompt { mode, label, ... }` | `region: { type: "form", fields: ... }` (and other modes) |
| `FrameBox { inner, border }` | nested region with frame style |
| `Style.PRIMARY` etc. | theme-resolved CSS class |

When v2 begins, the protocol upgrade is "serialise `LayoutTree` and
send it on the wire; client renders." The schema doesn't change;
only the rendering boundary moves.

This means **the v1.4 refactor is the v2 protocol's data model**.
Building it now buys both the architectural win immediately and the
v2 protocol stability when v2 starts.

---

## 11. Open questions

- **Phase enum extensibility.** Closed enum is right for v1.4 (all
  core phases known at compile time). Third-party extension
  screens want either (a) an open `Phase` registry, or (b) a
  separate `ExtensionPhase` concept. Defer to v1.6 when first
  external screen ships.
- **Per-user theme overrides at the layout level.** Currently theme
  is colours/borders/glyphs only. Could a future theme also pick
  *which screen layout variant* to render (e.g. a "compact mode"
  vs "spacious mode" of the menu)? Out of scope for v1.4; if
  demand emerges, add layout variants as a `LayoutTree` selector
  on `Theme`.
- **Screen stack depth.** Back-button transitions need a stack;
  cap at, say, 8 entries to prevent runaway. Spec the cap when
  PR-A lands.
- **Hot-reload of themes.** Deliberately deferred. The hook to add
  later: a `@RefreshScope` bean over `ThemeRegistry` that re-reads
  the YAML directory on signal.
- **Scripted screens via Rhino (forward note).** v1.4 explicitly
  rejects scripted screens (see §12 + ADR-025 rejected
  alternatives). The forward path *if* user-defined screens become
  desirable — captured here so the design isn't relitigated cold —
  is **embedding Rhino** (Mozilla JavaScript engine). Rhino is
  lighter and more sandboxable than the SpiderMonkey-fork
  ENiGMA½ uses, runs on any JVM, and the existing `Screen` /
  `Navigator` / `BbsContext` Java API maps cleanly to a JS object
  surface. A user could drop a `.js` file into a config directory
  defining a Screen, register it through a manifest, and the BBS
  would treat it as another `@Component`-discovered screen. The
  cost (security review, sandboxing surface, two-language
  codebase) is real, but Rhino avoids the worst of the
  embedded-interpreter complexity by being the JVM-native
  scripting engine. **Not implemented in v1.4 — recorded so the
  v1.6/v2 conversation has a concrete starting point rather than
  re-debating "should we even script."**

---

## 12. Out of scope (recorded so future work doesn't drift)

- Template engine (Mustache / Handlebars / Jinja).
- Hot-reload of layout files at runtime.
- Scripted screens (embedded JS / Lua / Python).
- Cross-cutting "plugin runtime" — Spring component-scan + the
  door protocol cover the extension surface.
- Externalising layout itself to YAML or JSON files.
- Per-screen ACL beyond what already exists (sysop checks).

These are deliberate non-decisions per ADR-025. Future ADRs can
revisit if real demand emerges.

---

## 13. Glossary

- **Screen.** A top-level renderable surface; one class per
  `Phase`. Owns its state, handlers, and layout production.
- **Phase.** Enum value identifying the current screen. Persisted
  per-session; used by the router for dispatch.
- **Transition.** What a handler returns to tell the router what
  to do next: stay, go to another phase, back, or end.
- **LayoutTree.** Data structure produced by a Screen describing
  what to render. Walked by the renderer.
- **LayoutNode.** Sealed type describing one piece of a layout
  (header, body, menu, prompt, etc.).
- **Style.** Abstract style enum (`PRIMARY` / `ACCENT` / `MUTED` /
  `BODY` / `ALERT` / `SUCCESS` / `BORDER`). Themes resolve these to
  concrete CGA colours.
- **Theme.** A palette + borders + glyphs YAML; loaded at startup.
  Active per session via the existing `#41` theme picker.
- **LayoutRenderer.** The component that walks a `LayoutTree`,
  applies the active theme, and produces `Frames.update` payloads.
- **Plugin screen.** A `Screen` implementation provided by a JAR
  outside the BBS source tree, discovered via Spring component-
  scan. v1.4 documents the contract; the open `Phase` registry is
  v1.6+.
