# Tree Screen Skinning Note

> **Status:** first slice implemented, further expansion planned  
> **Audience:** core contributors and operators planning screen
> customisation

This note captures the split between the existing raster-first skinning
model and the tree-screen skinning model for `ScreenApp` / tree-mode
screens. The first shell-first slice is now in place; the optional
decorative raster side remains future work.

## Why this note exists

VOIDcore now supports startup-loaded ANSI skins under `/instance/skins`.
That model works well for row-rendered screens because the server already
owns the final row surface.

Skin manifests may now target either exact screen names or wildcard include /
exclude sets, so one shell profile can cover every tree-mode screen while
still allowing exact per-screen overrides where needed.

Tree-mode screens are different:

- the server sends a structured UI tree with `Frames.tree(...)`
- the browser renders that tree into the terminal surface
- the server is describing layout, not pre-rasterising the final surface

So tree screens should not be forced back into a row-overlay model just to
reuse the current skin contract.

## Current rendering split

Today, screen rendering falls into three broad shapes:

- **row / fixed mode**
  - server sends final rows with `Frames.update(...)`
- **flow mode**
  - server still sends rows, but they are layout-rendered first and marked
    `mode: "flow"`
- **tree mode**
  - server sends an element tree with `Frames.tree(...)`
  - browser owns the final raster step

The current ANSI skin registry is a good fit for the first two, because they
still produce rows. It is not a natural fit for tree screens.

## Current tree-mode screens

At the time of writing, the built-in concrete tree-mode screens are:

- `document`
- `compose-post`
- `sysop-broadcast`

Custom screens built through `CustomScreenApp` can also be tree-mode.

## Design decision

Use a **split skin contract**:

- **row/flow skins** stay **raster-first**
- **tree skins** become **shell-first, raster-optional**

This keeps the benefits of the current skinning work without undermining the
reason tree mode exists.

## Row and flow skinning

For row and flow screens, keep the current model:

- per-screen `voidcore-skin.json`
- ANSI/text assets
- anchored slot rectangles
- server-side overlay into named regions

This is the right model because the server already owns the surface.

## Tree screen skinning

For tree screens, the primary skinning mechanism is **chrome and layout
shell**, not full-surface raster replacement.

The intent is:

- the tree still defines the UI structure
- the browser still performs the final render
- the skin decorates the shell around that tree

The first implemented slice now controls things like:

- wrapper variant
- header/footer chrome
- left padding / insets
- reserved top/bottom ANSI art blocks
- reserved left/right ANSI side rails
- stacked multi-block regions per side through asset lists

The later expansion point is:

- decorative background regions where safe

It should **not** mean arbitrary “paste this ANSI frame over the whole tree”
behaviour by default.

## Hybrid rule

Tree screen skins may still use raster art, but only where it is structurally
safe.

Rule of thumb:

- if the area is owned by the tree layout, skin it with wrappers, borders,
  tokens, and spacing
- if the area is intentionally reserved or blank, a raster/decorative asset
  may be mounted there

So the tree-side model is:

- **shell-first**
- **raster-optional**

## Proposed tree skin vocabulary

The current schema revolves around:

- `variant`
- `headerTitle`
- `headerRightAnnotation`
- `paddingLeft`
- `footerText`
- `footerStyle`

The likely future expansion still includes ideas like:

- `backgroundArtRegions`
- richer panel wrapper vocabularies

## Why not force tree screens into rows

Converting tree screens back into rows just for skinning would work against
the reason tree mode was introduced:

- tree mode exists so the server can describe structure
- the browser can rasterise and evolve the presentation
- richer UI composition becomes possible without hard-coding row output

So the correct extension path is not “flatten the tree so the old skin model
can be reused everywhere.” It is “let tree screens have a skin model that
matches their rendering model.”

## Implementation direction

When this is implemented, the likely shape is:

1. keep the current server-side ANSI skin registry for row/flow screens
2. add a tree-screen skin contract owned by the client render host
3. let tree screens opt into named wrappers / chrome slots
4. optionally allow safe decorative raster regions in reserved spaces

This should be treated as a complement to the current row-skin path, not a
replacement for it.
