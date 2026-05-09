# Instance Customization Guide

> **Audience:** operators and instance authors who want to customise a
> VOIDcore deployment without forking the engine.

This guide explains the three runtime customization hooks that are live
today:

- themes
- skins
- custom screens

For the higher-level contract and current limitations, see
[extending-voidcore.md](extending-voidcore.md). For the local worked
example, see
[../app/dev-instance/README.md](/Users/enzoreyes/proj/VoidCore/app/dev-instance/README.md).

## Overview

VOIDcore now reserves an instance root with these subdirectories:

```text
/instance/themes
/instance/skins
/instance/extensions
```

In this repo, the development sample lives at:

```text
app/dev-instance/
```

That sample is what the local `WS/360` theme/skin/extension uses.

## Themes

Themes are startup-loaded JSON manifests in `/instance/themes`. They let
an operator add new selectable themes without rebuilding the application.

Example:

```json
{
  "name": "ws360",
  "label": "WS/360",
  "variables": {
    "--bg": "#071018",
    "--fg": "#9ee7ff",
    "--bright-cyan": "#91f0ff",
    "--status-text-fg": "#4ecf96",
    "--widget-header-title-fg": "#91f0ff"
  },
  "effects": {
    "flicker": false,
    "noise": false
  }
}
```

The sample theme is:

- [app/dev-instance/themes/ws360.json](/Users/enzoreyes/proj/VoidCore/app/dev-instance/themes/ws360.json)

### Theme fields

- `name`: machine name used by `setTheme(...)` and the theme picker
- `label`: optional human-readable display name
- `variables`: supported CSS-variable overrides
- `effects`: optional CRT effect toggles

### Supported effects

- `scanlines`
- `flicker`
- `noise`

### Supported variables

The theme registry now accepts only the documented token set. Common ones:

- palette: `--bg`, `--fg`, `--bright`, `--bright-cyan`, `--bright-yellow`
- CRT: `--crt-glow-rgb`
- status bar: `--status-text-fg`, `--status-key-fg`
- widget chrome: `--widget-header-title-fg`, `--widget-key-menu-key-fg`
- editor: `--widget-editor-fg`, `--widget-editor-cursor-bg`
- markdown/text tokens: `--tok-heading-fg`, `--tok-link-fg`, `--tok-code-fg`

If a manifest uses unsupported variables, they are ignored with a warning at
startup rather than crashing the instance.

### How to use a theme

1. Add a `*.json` file under `/instance/themes`.
2. Restart the app.
3. Use `[T]` in the main menu or call `ctx.setTheme("your-theme")` from a
   custom screen.

## Skins

Skins are startup-loaded per-screen overlays in `/instance/skins`. They are
for presentation changes, not behavior changes.

Each skin directory contains:

- `voidcore-skin.json`
- one or more ANSI/text assets

Example layout:

```text
/instance/skins/ws360-global/
  voidcore-skin.json
  banner.ans
  main.ans
  top.ans
  left.ans
  right.ans
  bottom.ans
```

### Skin targeting

A skin can target screens in three ways:

- `screenName`
- `screenNames`
- `includeScreens` with optional `excludeScreens`

Exact matches override wildcard matches. That lets you define one global
house style and then replace specific screens with exact overrides.

### Banner policy

Skins can also set:

```json
"bannerPolicy": "always_compact"
```

Supported values:

- `always_full`
- `auto_compact`
- `always_compact`

This policy is resolved from the matching skin, so an exact screen override
can choose a different banner behavior than the wildcard/default profile.

### Row/flow screens

Row-rendered and flow-rendered screens can override:

- `banner`
- `main`

The common content slot for the `main` region is:

- `body`

Example:

```json
{
  "screenName": "main-menu",
  "bannerPolicy": "always_compact",
  "banner": {
    "asset": "banner.ans"
  },
  "main": {
    "asset": "main.ans",
    "slots": [
      {
        "name": "body",
        "row": 5,
        "col": 4,
        "width": 72,
        "height": 18
      }
    ]
  }
}
```

Sample files:

- [app/dev-instance/skins/ws360-global/voidcore-skin.json](/Users/enzoreyes/proj/VoidCore/app/dev-instance/skins/ws360-global/voidcore-skin.json)
- [app/dev-instance/skins/menu/voidcore-skin.json](/Users/enzoreyes/proj/VoidCore/app/dev-instance/skins/menu/voidcore-skin.json)
- [app/dev-instance/skins/login/voidcore-skin.json](/Users/enzoreyes/proj/VoidCore/app/dev-instance/skins/login/voidcore-skin.json)

### Tree-mode screens

Tree-mode screens use a shell-first skin contract instead of raw row
compositing.

Tree skin features include:

- `variant`
- `headerTitle`
- `headerRightAnnotation`
- `paddingLeft`
- `topAsset` / `topAssets`
- `leftAsset` / `leftAssets`
- `rightAsset` / `rightAssets`
- `bottomAsset` / `bottomAssets`
- `footerText`
- `footerStyle`

Example:

```json
{
  "screenName": "ws360/demo",
  "bannerPolicy": "always_compact",
  "tree": {
    "variant": "console",
    "headerTitle": "WS/360 PRESENTATION SHELL",
    "headerRightAnnotation": "TREE MODE",
    "paddingLeft": 1,
    "topAssets": ["top.ans", "top-accent.ans"],
    "leftAssets": ["left.ans", "left-lower.ans"],
    "rightAssets": ["right.ans", "right-lower.ans"],
    "bottomAssets": ["bottom.ans", "bottom-note.ans"],
    "footerText": "WS/360 shell chrome wraps the tree while the browser still owns layout and raster.",
    "footerStyle": "bright_cyan"
  }
}
```

Sample file:

- [app/dev-instance/skins/ws360-demo/voidcore-skin.json](/Users/enzoreyes/proj/VoidCore/app/dev-instance/skins/ws360-demo/voidcore-skin.json)

For the reasoning behind tree-mode shell skinning, see
[tree-screen-skinning-note.md](tree-screen-skinning-note.md).

## Custom screens

Custom screens are startup-discovered from `/instance/extensions`.

Current manifest convention:

```text
/instance/extensions/<slug>/voidcore-extension.json
```

Sample files:

- [app/dev-instance/extensions/ws360/voidcore-extension.json](/Users/enzoreyes/proj/VoidCore/app/dev-instance/extensions/ws360/voidcore-extension.json)
- [app/dev-instance/extensions/ws360/demo.js](/Users/enzoreyes/proj/VoidCore/app/dev-instance/extensions/ws360/demo.js)

### Manifest shape

Example:

```json
{
  "slug": "ws360",
  "label": "WS/360 Demo",
  "version": "0.1.0",
  "screens": [
    {
      "screenName": "ws360/demo",
      "label": "WS/360 Demo",
      "entrypoint": "demo.js",
      "capabilities": [
        "documents.read",
        "extensions_data.user",
        "effects.theme"
      ],
      "documentTypes": ["note", "article"]
    }
  ]
}
```

### Screen naming

Custom screen names are normalized to lowercase and must avoid collisions
with built-in or other custom screen names. A route-safe namespaced shape
such as `ws360/demo` is recommended.

### GraalJS registration

The current in-process runtime uses GraalJS with a curated proxy boundary.
Scripts register with:

```js
voidcore.registerScreen({
  onEnter(ctx) {
    ctx.banner("WS/360 DEMO");
    ctx.mainText(["hello"]);
    ctx.promptKeystroke("cmd:", "Q");
  },
  onKey(ctx, key) {
    if (key === "Q") ctx.pop();
  }
});
```

### What `ctx` exposes

The JS callback receives a unified proxy host object. Common calls:

- `ctx.banner(...)`
- `ctx.mainText(...)`
- `ctx.mainTree(...)`
- `ctx.render(...)`
- `ctx.promptKeystroke(...)`
- `ctx.push(...)`
- `ctx.pushCore(...)`
- `ctx.pop()`
- `ctx.docBySlug(...)`
- `ctx.docsByType(...)`
- `ctx.putForCurrentUser(...)`
- `ctx.getForCurrentUser(...)`
- `ctx.setTheme(...)`

Tree helpers are also available through:

- `ctx.el`
- `ctx.elements`
- `ctx.ui.el`
- `ctx.ui.elements`

### Persistence

Extension-owned state should go through `extensions_data`, not through
session scratch state. Current scopes are:

- global
- current user

### Current limitations

Custom screens are real and usable now, but a few boundaries are still
intentional:

- no direct filesystem access from scripts
- no direct network access from scripts
- no direct Java class access
- no hot reload
- no general plugin marketplace

## Recommended workflow

If you are building an instance from scratch:

1. Start with a theme in `/instance/themes`.
2. Add a wildcard skin in `/instance/skins` for the house look.
3. Add exact screen overrides only where needed.
4. Add a custom screen manifest and script under `/instance/extensions`.
5. Use the sample `app/dev-instance` files as your first reference.

That gets you the most leverage with the least amount of instance-specific
code.
