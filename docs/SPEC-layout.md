# SPEC: Layout / Flow rendering mode

Concrete spec for the layout abstraction described in **ADR-031**.
Rendering mode is per-region; `FIXED` is what every region uses
today, `FLOW` is the new mode for long-form text.

## §1 Goals

- Unblock long-form text rendering (NFO, announcement body, post body,
  voidmail body) without forcing the server to do hand-rolled
  word-wrap on every paint.
- Establish the abstraction in V1.5 *server-side only* — wire
  format unchanged, every screen except the four below stays on
  `FIXED`.
- Make V2's protocol extension purely additive: add a `tree`
  payload variant; client-side renderer is new but contained.
- Stay small. Five elements + two decorators. No layout managers.

## §2 Vocabulary

Seven types. All immutable records (or sealed-type leaves).

```
sealed interface Layout {
  record Fixed(List<Row> rows) implements Layout {}
  record Flow(Element root, int canvasCols) implements Layout {}
}

sealed interface Element {
  record VStack(List<Element> children, int gap) implements Element {}
  record Text(String content, String style) implements Element {}
  record Para(String content, String style) implements Element {}
  record Rule() implements Element {}
  record Spacer(int rows) implements Element {}
  record Padded(Element child, int leftCols) implements Element {}
  record Styled(Element child, String style) implements Element {}
}
```

### §2.1 `VStack`

Vertical container. Children render top-to-bottom in declaration
order. `gap` is the number of blank rows between siblings (default
0). The implicit root container of any `Flow`.

### §2.2 `Text`

One row. `content` does not wrap — overflow is truncated at
`canvasCols` with a trailing ellipsis. Use for short strings:
labels, headers, key:value pairs.

### §2.3 `Para`

Wrapping paragraph. **Hard-break semantic:** every `\n` ends a
wrapping segment. Each segment word-wraps independently against
`canvasCols`. Empty segments — produced by `\n\n`, a leading
`\n`, a trailing `\n`, or runs of newlines — emit a blank row
each, preserving the visible gap the author typed.

Examples:

- `"line one\nline two"` → two rows: `line one`, `line two`.
  (Each line typed on its own line stays on its own line.)
- `"a\n\nb"` → three rows: `a`, blank, `b`.
- `"a\n\n\nb"` → four rows: `a`, blank, blank, `b`.
- `"the quick brown fox"` at `cols=10` → `the quick`, `brown fox`
  (single segment, wrapped by word).

Word-break is space-only; no hyphenation. Long words exceeding
`canvasCols` overflow on a single row (better one ugly line than
a broken word — matches BBS aesthetic).

**Why hard-break is the default:** an earlier draft of this spec
specified a soft-break semantic (single `\n` joins with a space,
double `\n` is the paragraph break). It was wrong. Almost no real
user-typed BBS content uses single newlines as soft breaks —
seed announcements, posts, VoidMail bodies, NFO files all use
single newlines as hard line endings, often with manual alignment
or list separators that the soft-break flattens into prose. The
hard-break default matches the intent of every author who writes
a multi-line body and expects to see multiple lines.

If a screen genuinely wants soft-wrap (rare), it can join its
`\n`s into spaces before passing the string to `Para`.

### §2.4 `Rule`

Horizontal divider. Renders as `+` `-`×N `+` or just `-`×N
depending on context (probably just `-`×N for v1.5 — themeable
later). One row.

### §2.5 `Spacer(rows)`

`rows` blank rows. Default 1.

### §2.6 `Padded(child, leftCols)`

Decorator. Indents the child by `leftCols` spaces. Internally:
render the child against a canvas narrowed to `canvasCols -
leftCols`, then prefix every emitted row with `leftCols` spaces.
Padded composes — `Padded(Padded(x, 2), 2)` is `Padded(x, 4)`.

### §2.7 `Styled(child, style)`

Decorator. Overrides the style on every span the child emits.
Innermost `Styled` wins (a styled child of a styled parent gets
the inner style). `style` is a theme string (`bright_yellow`,
`grey`, `default`, etc.) — same vocabulary `Frames.span(text,
style)` already uses.

## §3 Rendering — V1.5 (server-side, FIXED-compatible wire)

Screens return a `Layout`. The renderer turns it into a list of
`Row` frames:

```java
public List<Row> render(Layout layout) {
  return switch (layout) {
    case Fixed f -> f.rows();
    case Flow flow -> renderFlow(flow.root(), flow.canvasCols(), 0);
  };
}
```

Where `renderFlow(element, cols, baseRow)` walks the tree depth-
first, accumulating `Row` frames at increasing row indices.

### §3.1 The walk

For each element:

```
VStack(children, gap):
  rowN := 0
  for each child:
    childRows := renderFlow(child, cols, rowN)
    emit childRows
    rowN := rowN + childRows.size + gap

Text(s, style):
  emit one Row at rowN with Frames.span(truncate(s, cols), style)

Para(s, style):
  segments := s.split("\n", -1)
  for each segment:
    if segment is empty:
      emit one blank Row at rowN; rowN++
    else:
      lines := wrap(segment, cols)
      for each line: emit one Row at rowN with Frames.span(line, style); rowN++

Rule:
  emit one Row at rowN with Frames.span(repeat("-", cols), "default")

Spacer(n):
  emit n blank Rows starting at rowN

Padded(child, leftCols):
  childRows := renderFlow(child, cols - leftCols, 0)
  for each childRow:
    prefix every span with leftCols spaces
    emit at rowN + childRow.row

Styled(child, style):
  childRows := renderFlow(child, cols, rowN)
  for each childRow:
    rewrite every span's style attribute to `style`
    emit unchanged otherwise
```

### §3.2 The wrapper

`wrap(text, cols)` is greedy word-wrap on space:

```
words := text.split(" +")
lines := []
current := ""
for each word:
  candidate := current.isEmpty() ? word : current + " " + word
  if candidate.length <= cols:
    current := candidate
  else:
    if current.isEmpty():
      // single word longer than cols — overflow on one line
      lines.add(word)
      current := ""
    else:
      lines.add(current)
      current := word
if current.nonEmpty: lines.add(current)
return lines
```

No hyphenation. No look-ahead. ~20 lines of Java.

### §3.3 Canvas

Default `canvasCols` is **80**. A screen can override per `Flow`
construction (e.g. for a known wider banner area). The `main`
region in the existing UI is sized for 80 cols; this matches.

V1.5 does not consult the client's actual canvas — that's V2's
problem. If the client's canvas is wider, content renders at 80
cols; if narrower, the client truncates per its existing FIXED
behaviour. Both regress gracefully.

## §4 Wire format — V1.5

The frame envelope already supports `region`, `seq`, and `rows`.
Add an optional `mode` field:

```json
{
  "region": "main",
  "seq": 21,
  "mode": "flow",
  "rows": [
    {"row": 0, "spans": [{"text": "Hello", "style": "bright_yellow"}]},
    ...
  ]
}
```

V1.5 clients see `mode: "flow"` but don't act on it — the `rows`
are pre-rendered exactly as they would be in `FIXED` mode, so the
client paints them the same way. The `mode` field is informational
in V1.5; clients can use it for debug logging.

`mode` defaults to `"fixed"` if absent. Existing screens emitting
plain `FIXED` frames are unaffected.

## §5 Wire format — V2 (forward)

V2 extends the envelope with a `tree` payload variant:

```json
{
  "region": "main",
  "seq": 21,
  "mode": "flow",
  "tree": {
    "type": "vstack",
    "gap": 0,
    "children": [
      {"type": "text", "content": "== NFO ==", "style": "bright_yellow"},
      {"type": "spacer", "rows": 1},
      {"type": "para", "content": "Long body text...", "style": "default"}
    ]
  }
}
```

Client-side renderer measures against the actual canvas and lays
out. Resize triggers a re-render — no server round-trip needed.

V2 clients accept either `rows` or `tree` payload variants
independently. V1.5 clients seeing a `tree`-payload frame fall
back to the `rows` payload if the server sends both, or treat the
frame as a no-op if the server has stopped emitting `rows`.
Migration path: in V2 the server emits `tree` only; old clients
must upgrade. The `mode` field is the version handshake.

The full `tree` payload schema (JSON shape per element type) is
defined in V2's protocol spec — out of scope for this document
beyond the example above.

## §6 Migration plan

V1.5 ships the abstraction and migrates **one screen** as the
reference: `ReleaseViewScreen` NFO body. Picking criteria:

- Body is the longest text on screen, currently in a 64-col fixed-
  width box with manual truncation.
- Sysop-editable via the new-file walk and the edit-NFO step, so
  the migration exercises both inbound NFO sources.
- Single migration target — small enough to land in one PR.

After landing, `BulletinViewScreen.body`,
`ThreadViewScreen.posts`, and `NetmailReadScreen.body` are
follow-ups; each is ~30 lines of screen change and identical
shape.

The migrated screen builds a `Flow` tree like:

```java
Layout layout = new Flow(
    new VStack(List.of(
        new Text("  == " + f.filename() + " · " + f.title() + " ==", "bright_yellow"),
        new Spacer(1),
        // V5 metadata block — only the set fields
        metadataBlock(f),
        new Rule(),
        new Padded(new Para(f.nfoText(), "default"), 2),
        new Rule(),
        new Spacer(1),
        footerLine(f)
    ), 0),
    80
);
List<Row> rows = layoutRenderer.render(layout);
ctx.send(Frames.update("main", 21, rows, "flow"));
```

`Frames.update` gains an optional `mode` parameter; existing
calls keep emitting `"fixed"` mode (or omit entirely).

## §7 Themes

Unchanged. Style strings on `Text`, `Para`, and `Styled` are the
same theme handles every existing `Frames.span` call uses. The
client-side theme CSS resolves them per-theme as today. No new
style attributes — the win is layout, not appearance.

## §8 Out of scope (deliberately)

- `HStack`, `Grid`, multi-column layouts. Existing tabular
  screens stay on `FIXED`.
- `TextArea` / `Input` / cursor + selection state. Editing is its
  own ADR alongside V2 cursor-pos events.
- Images, icons, custom fonts. ASCII BBS forever.
- Animation, scroll regions, modal overlays. Not v1.5; not
  obviously v2 either.
- Real responsive resize on V1.5. The renderer wraps at the
  server-known canvas; browser resize doesn't trigger anything.
  That's the V2 win.

## §9 File layout (V1.5)

```
app/src/main/java/io/aeyer/voidcore/ws/flow/layout/
  Layout.java          // sealed interface; Fixed, Flow records
  Element.java         // sealed interface; the 7 element records
  LayoutRenderer.java  // walk + emit Row frames
  WordWrap.java        // §3.2 helper
```

Plus a small extension to `Frames.update` to accept an optional
`mode` parameter.

Tests in
`app/src/test/java/io/aeyer/voidcore/ws/flow/layout/LayoutRendererTest.java`
covering each element type and the renderer's row-numbering /
indent / style-override behaviour.

## §10 V2 forward references

Items recorded here so the V2 work can pick them up without
re-litigation:

- Protocol envelope `tree` payload variant (§5).
- Client-side renderer + measurement against actual canvas.
- Resize → re-render (purely client-side; no server round-trip).
- **Screen-owned regions** (ADR-032). V1 hardcodes four region
  names ({@code banner / main / prompt / notify}) in the wire
  protocol; V2 makes the region set part of the screen's
  declaration. A screen says "I have a {@code header} and a
  {@code main}, no banner" or "I have a sidebar layout" and the
  client lays out accordingly. Pairs with this spec: ADR-031
  defines what's *inside* a region (Flow tree of Elements);
  ADR-032 defines what the regions themselves are. Same Element
  vocabulary scaled up one level.
- Editing primitives — separate ADR.
- `HStack` and column layouts — additive when a screen genuinely
  needs them.
- Style expansion (icons, code blocks, monospace toggles) —
  separate concern from layout.
