# dev-instance

This directory is the worked example instance root used by the local
development build. It exists to exercise the overlay/customisation
contract end to end without requiring a separate private repo.

The current sample is the `WS/360` instance.

## Layout

```text
app/dev-instance/
  themes/
  skins/
  extensions/
```

## Theme

The runtime-loaded sample theme lives at:

- `themes/ws360.json`

That manifest defines:

- the public theme name (`ws360`)
- the label shown in the theme picker (`WS/360`)
- the supported CSS variable overrides
- optional CRT effect toggles

## Skins

The sample skin set is split into one wildcard profile plus a few exact
screen overrides:

- `skins/ws360-global/`
  - wildcard `includeScreens: ["*"]`
  - house-style banner/main/tree chrome for all screens
- `skins/menu/`
  - exact `main-menu` override
- `skins/login/`
  - exact `login-handle` override
- `skins/ws360-demo/`
  - exact `ws360/demo` tree-shell override

Current banner policy for the sample is `always_compact`, so the WS/360
instance keeps a one-line header instead of tall ANSI banner art.

## Extension

The manifest-backed sample extension lives at:

- `extensions/ws360/voidcore-extension.json`
- `extensions/ws360/demo.js`

That pair demonstrates:

- registering a named custom screen
- running through the GraalJS host bridge
- rendering rows/tree content through proxied VOIDcore APIs
- storing extension-owned state through `extensions_data`

## Practical editing notes

- Change `themes/ws360.json` when you want to alter the palette or widget
  chrome.
- Change `skins/ws360-global/*` when you want to alter the default WS/360
  look applied across the board.
- Change `skins/menu/*` or `skins/login/*` when you want those exact screens
  to look different from the house profile.
- Change `extensions/ws360/demo.js` when you want to exercise the JS host
  API or prototype a new custom screen.

This directory is sample content for local development, not the final
production overlay repo shape.
