# Extending VOIDcore

> **Audience:** operators running their own VOIDcore instance who want
> to add scene-specific document types, seed data, or branding without
> forking the core engine.

VOIDcore keeps the engine small on purpose. Scene-specific types, seed data,
and specialty screens should live outside core whenever they are not broadly
useful to every deployment.

Today, VOIDcore has a **small, explicit overlay contract**. It is real,
but it is intentionally narrower than a full plugin system.

## Current contract at a glance

Supported extension points today:

- **Branding/config hooks** via environment variables such as `BBS_NAME`,
  `BBS_TAGLINE`, `BBS_SYSOP_HANDLE`, and related settings in
  [`application.yml`](../app/src/main/resources/application.yml)
- **Instance feature shaping** via `BBS_DISABLED_SCREENS`, which lets an
  operator trim built-in surfaces without forking core
- **Overlay SQL migrations** by extending Flyway locations with an
  operator-mounted path
- **Sidecar doors** via the door protocol for non-JVM extensions that live
  beside the BBS rather than inside it
- **Core-supported custom screen routing seam** for future in-process
  extensions: the navigator stack can now carry named custom screens in
  addition to built-in `Phase` routes, with normal push/pop and resume
  semantics
- **Startup extension manifest discovery** under an external instance root:
  VOIDcore now scans `/instance/extensions/*/voidcore-extension.json` for
  named custom-screen registrations
- **Engine-neutral script host seam** for manifest-backed screens:
  discovered screens now resolve through a curated host API rather than
  registry-local placeholder logic
- **Extension-owned JSON state storage contract** via `extensions_data`,
  reserved for future in-process extensions rather than ad hoc reuse of
  unrelated core/session scratchpads

Not supported yet:

- a supported scripting runtime that turns manifest-backed screens into
  real scripted/application screens
- hot-reload, scripting runtimes, or a dynamic plugin marketplace

This means the current contract is strong enough for:

- scene-specific schemas and seed data
- deployment-specific branding and configuration
- sidecar applications that speak the door protocol

It is **not yet** the final operator-facing answer for instance-supplied
Java or scripted screens.

For the practical setup walkthrough covering themes, skins, and custom
screens, start with
[instance-customization-guide.md](instance-customization-guide.md).

## Recommended repo boundary

The intended split is:

- **Core repo (`VoidCore`)** owns the typed-document substrate, chat/mail/
  ACL/feature-toggle infrastructure, generic built-in screens, and the door
  protocol
- **Private instance overlay repo** owns scene-specific schema/data, instance
  branding, deployment glue, and later any instance-only code once the Java
  extension boundary is formalised

Examples that belong in an overlay repo:

- a `release` schema for a music-scene catalogue
- reference-instance seed data
- deployment-side Compose overrides and backup tooling
- instance-only text, banner copy, and local theming

Examples that should stay in core:

- generic document validation and revision behavior
- reusable message-board/chat/VoidMail/door mechanics
- feature toggles and access-control primitives
- screens that make sense for most installations

## Overlay migrations

By default, VOIDcore only loads the built-in migration chain:

```text
classpath:db/migration
```

An operator can opt into overlay-supplied repeatable migrations by setting:

```text
VOIDCORE_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/instance/migrations
```

The core repo does not ship an `/instance` directory or any scene-specific
content for production use. The contract is intentionally filesystem-based so
deployments can pin their own overlay repo or bind-mount custom migrations
without forking core.

For local development and testing, this repo now also ships a worked example
instance root at [`../app/dev-instance`](/Users/enzoreyes/proj/VoidCore/app/dev-instance).
It exists to exercise the extension seams end-to-end and should be treated as
sample content, not as the canonical production overlay location.
The sample instance is documented in
[`../app/dev-instance/README.md`](/Users/enzoreyes/proj/VoidCore/app/dev-instance/README.md).

The reserved directory layout under the instance root is now:

```text
/instance/skins
/instance/themes
/instance/extensions
```

All three roots now participate in the extension boundary:

- `/instance/themes`
  - startup-loaded `*.json` theme manifests
  - additive to the built-in themes
  - themes only need to define CSS-variable overrides and optional CRT
    effect toggles; no rebuild is required
- `/instance/skins`
  - startup-loaded per-screen directories containing `voidcore-skin.json`
    plus ANSI/text assets
  - manifests can now target screens in three ways:
    - exact `screenName`
    - exact `screenNames` list
    - wildcard `includeScreens` plus optional `excludeScreens`
  - manifests can also set `bannerPolicy`:
    - `always_full`
    - `auto_compact`
    - `always_compact`
  - `bannerPolicy` is resolved from the matching skin, so an exact screen skin
    can override the wildcard/default policy for a specific surface
  - exact screen matches override wildcard profiles, which lets an operator
    define one “house style” skin and still specialize selected screens
  - row-rendered screens can now be selectively or globally overridden on the
    `banner` and `main` regions through shared context dispatch
  - the common `main`-region content slot is `body`; manifests that define
    it can wrap the built-in content without custom Java screen code
  - tree-mode screens now have an initial shell-first path: manifests can
    wrap `ScreenApp` trees with a fixed chrome variant plus optional
    header/footer text and left padding
  - tree-mode shells can also mount safe decorative ANSI/text blocks above
    or below the live widget tree through reserved `topAsset` /
    `bottomAsset` lines, and can mount decorative side rails through
    `leftAsset` / `rightAsset`
  - each side also supports plural lists (`topAssets`, `leftAssets`,
    `rightAssets`, `bottomAssets`) when an instance wants more than one
    decorative region in the same shell zone
  - invalid skins still fall back to the built-in screen render
  - tree-mode skinning still has a larger follow-on design for safe raster
    regions: see
    [tree-screen-skinning-note.md](tree-screen-skinning-note.md)
- `/instance/extensions`
  - startup-loaded extension manifests and script entrypoints

The current expectation is that an overlay repo provides **repeatable** or
otherwise deployment-owned migrations on that mounted path, while the core
repo continues to own the canonical versioned migration chain.

## Example compose override

One straightforward deployment pattern is a compose override that mounts a
private overlay directory into the app container:

```yaml
services:
  app:
    volumes:
      - ./instance/migrations:/instance/migrations:ro
    environment:
      VOIDCORE_FLYWAY_LOCATIONS: >-
        classpath:db/migration,filesystem:/instance/migrations
```

## Other supported hooks

### Branding and instance copy

Branding is intentionally operator-controlled through environment-backed
configuration. Today that includes:

- `BBS_NAME`
- `BBS_TAGLINE`
- `BBS_DESCRIPTION`
- `BBS_SUBTAGLINE`
- `BBS_SYSOP_HANDLE`
- `BBS_FIDO_ADDR`
- `BBS_DIAL_NUMBER`
- `BBS_CONNECT_RATE`

These are enough to let an operator make the running board read like an
instance rather than like the generic engine.

### Feature shaping

Operators can disable selected built-in surfaces with:

```text
BBS_DISABLED_SCREENS=files,doors
```

This is not a replacement mechanism; it is a **shaping** mechanism. It lets
an instance trim the built-in surface area without pretending the built-ins no
longer exist in core.

### Sidecar doors

For non-JVM extension work, the door protocol is already a stable path.
Doors are the recommended way to ship custom interactive applications today
without waiting for a future in-process screen/plugin boundary.

## Recommended responsibilities

Good overlay candidates:

- scene-specific document types such as `release`
- seed data that belongs to one community only
- highly themed screens that are really product features for one instance
- branding assets and local copy

Keep in core:

- generic typed-document substrate behavior
- chat, mail, message board, polls, and door protocols
- ACL and feature-toggle infrastructure
- reusable screens that make sense for most deployments

## Current limitations

The most important present limitation is that **overlay-supplied Java screen
code is not a supported operator contract yet**. The engine now has an
internal route seam for named custom screens, but that is still infrastructure
for the next extension phase, not a promise that private screen classes,
manifests, or scripts are ready for general use.

In practical terms:

- SQL/data overlays: supported
- branding/config overlays: supported
- door sidecars: supported
- named custom-screen routing inside the engine: present
- private/operator-supplied in-process screen plugins: not yet supported

## Custom screen seam

The engine now distinguishes between:

- **core screen routes**, still identified by the `Phase` enum
- **custom screen routes**, identified by a stable string name such as
  `aeyer/releases`

That means the navigator stack, router dispatch, and reconnect/restart
restore path can already treat a named custom screen as a first-class stack
entry.

### Naming rules

Custom screen names are normalised to lowercase and must:

- be non-blank
- avoid collisions with built-in screen names
- avoid collisions with other custom screens
- match a conservative route-safe pattern such as `aeyer/releases`

### Resume shape

The reconnect/restart persistence shape for a custom screen is:

```json
{
  "kind": "custom_screen",
  "screen": "aeyer/releases"
}
```

If that named screen is unavailable during restore, VOIDcore degrades safely
back to the main menu.

### Startup discovery convention

The current discovery convention is:

```text
/instance/extensions/<extension-slug>/voidcore-extension.json
```

A minimal manifest can declare screens like:

```json
{
  "slug": "aeyer",
  "label": "AEYER Overlay",
  "version": "0.1.0",
  "screens": [
    {
      "screenName": "aeyer/releases",
      "label": "AEYER Releases",
      "entrypoint": "releases.js",
      "capabilities": ["documents.read", "extensions_data.user"],
      "documentTypes": ["release"]
    }
  ]
}
```

At this stage, discovery and collision validation are real, and manifest-backed
screens register through an explicit runtime boundary. In other words:

- the screen name is registered
- the route can be pushed and restored
- core stores the manifest metadata separately from the registry itself
- the runtime now looks for a host that can load the screen
- if no host claims it, the default runtime falls back to a conservative
  placeholder implementation
- the user sees a clear “registered but runtime not installed yet” screen
- the actual script/app execution layer still lands later

### Extension-owned state

VOIDcore now reserves a dedicated `extensions_data` table for future in-process
extensions. The intent is:

- extension-owned state lives there, not in `sessions.app_state`
- data is namespaced by extension slug
- v1 scopes are `global` and `user`
- values are JSONB documents

This storage contract is in place ahead of the scripting runtime so future
extension APIs can target a stable persistence surface from the start.

### Current host API seam

Manifest-backed screens now target a curated Java-side host contract with:

- `ui`
  - banner updates
  - text/tree rendering in the main region
  - prompt control
  - transient notifications
- `navigation`
  - `pop`
  - `pushCustom`
  - `pushCore`
- `session`
  - authenticated/user/sysop summary
  - current route key
- `documents`
  - read-only lookup by id/slug
  - read-only list-by-type
  - normal visibility checks still apply
- `data`
  - extension-owned `global` and current-user JSON state via `extensions_data`
- `effects`
  - safe client effects such as open URL, theme switch, and clipboard copy

This is intentionally the host seam only. The first JavaScript runtime adapter
still lands later.

## GraalJS adapter

The first in-process adapter now targets GraalJS, but it keeps the public
contract engine-neutral:

- the runtime reads the script file from Java
- the script does **not** get direct filesystem or network access
- the script does **not** get direct Java class lookup or arbitrary host-object
  access
- every exposed capability is presented through guest-side proxy objects

### Registration shape

Current Graal-backed scripts register themselves like:

```js
voidcore.registerScreen({
  onEnter(ctx) {
    ctx.banner("AEYER Releases");
    ctx.mainText(["hello from JS"]);
    ctx.promptKeystroke("cmd:", "Q");
  },
  onKey(ctx, key) {
    if (key === "Q") ctx.pop();
  }
});
```

### Proxy rule

The important boundary is:

- Java internals are not handed directly to the script
- no repositories, Spring beans, router objects, or raw services are exposed
- the script only sees proxied APIs and plain guest values

That means any new script-visible capability should be added by extending the
host proxy surface, not by passing another host object through to GraalJS.

### Unified bridge

The current Graal adapter now exposes one unified host bridge object to the
script callback. It still includes namespaced views such as:

- `ctx.ui`
- `ctx.navigation`
- `ctx.documents`
- `ctx.extensionsData`
- `ctx.effects`

But the most common calls are also available directly on `ctx`, for example:

- `ctx.banner(...)`
- `ctx.mainText(...)`
- `ctx.mainTree(...)` / `ctx.render(...)`
- `ctx.promptKeystroke(...)`
- `ctx.push(...)`
- `ctx.pushCore(...)`
- `ctx.pop()`
- `ctx.docBySlug(...)`
- `ctx.putForCurrentUser(...)`

That gives scripts one consistent API point while preserving the internal
groupings underneath it.

### Element helpers

The internal tree UI model is now exposed through helper constructors on:

- `ctx.el`
- `ctx.elements`
- `ctx.ui.el`
- `ctx.ui.elements`

Current helpers mirror the supported `Element` vocabulary:

- `vstack(children, gap)`
- `text(content, style)`
- `para(content, style)`
- `rule()`
- `spacer(rows)`
- `padded(child, leftCols)`
- `styled(child, style)`
- `header(title, rightAnnotation)`
- `statusLine(mode, left, right)`
- `keyEntry(key, label)`
- `keyMenu(entries)`
- `textField(id, label, value, maxLength, readOnly)`
- `editor(id, content, mode, syntaxMode, readOnly)`
- `form(id, children, focusedChildId)`

Example:

```js
voidcore.registerScreen({
  onEnter(ctx) {
    ctx.render(
      ctx.el.vstack([
        ctx.el.header("AEYER", "JS"),
        ctx.el.text("Hello from the internal tree model", "bright_cyan"),
        ctx.el.keyMenu([
          ctx.el.keyEntry("Q", "Back")
        ])
      ], 1),
      null
    );
    ctx.promptKeystroke("cmd:", "Q");
  }
});
```

### What this does and does not mean

This seam means the engine is now structurally capable of handling
first-class named custom screens.

It does **not** yet mean that operators should expect a finished public
manifest format, a scripting runtime, or a drop-in plugin marketplace. Those
pieces still land in later tickets.

## Forward path

The near-term goal is not a grand plugin runtime. It is a clearer,
documented contract for what an instance repo may own without forking core.

That next step likely includes:

- documenting which built-in screens are shapeable vs replaceable
- defining the boundary for future instance-supplied Java screen code
- removing scene-specific built-ins from core where they are really
  reference-instance concerns

Until then, the honest contract is: **use overlays for data, branding,
deployment layering, and doors; keep core behavior changes in the core repo.**
