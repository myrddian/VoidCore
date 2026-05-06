# VOIDcore — Documents Specification

**Version:** 1.0
**Status:** Draft for implementation (v1.5 milestone)
**Companion to:** `SPEC.md` (BBS↔client protocol, `voidcore-node-v1`)
**Decisions referenced:** ADR-023 (information primacy), ADR-024
(Anchor as optional retrieval addon)

> **Information is the substrate of VOIDcore.** This document
> defines what "information" is, how documents are stored, how they
> are navigated, and how they are searched. The companion ADRs
> explain *why* this shape was chosen over filesystems, wikis, and
> spaces-as-containers.
>
> The substrate is opinionated and small: one global pool of typed
> documents, no folders, no spaces, no paths. Navigation is faceted.
> Conversation primitives (chat, threads, VoidMail, one-liners) stay
> their own thing.

---

## 1. Concept

A **document** is a piece of information someone curated:

- A how-to write-up about sidechain compression.
- A glossary entry defining "EBM."
- A link collection of recommended labels.
- A release page with NFO metadata pointing at Bandcamp.
- A personal note: "what I'm listening to this week."
- An article expressing an opinion.

Documents are **typed**, **author-owned**, **structured**, and
**queryable**. They live in one global pool with metadata that
enables faceted navigation. They are not files, not pages, not
posts.

### What documents are NOT

- **Not files.** VOIDcore doesn't host files; the existing
  "file area" (`#27`/`#81`) becomes documents of `kind=release`
  pointing at external hosts.
- **Not pages.** No URL structure, no path-as-canonical-id, no
  hierarchical folders.
- **Not posts.** Posts (forum, thread replies, chat messages,
  one-liners) are *conversation*. Conversation is ephemeral and
  addressed; documents are durable and curated. Different
  primitive.
- **Not wiki pages.** No flat-namespace assumption. Documents have
  rich metadata and are navigated by faceting that metadata, not
  by following links from an index page.

### Conversation vs information

This boundary is structural — keep it sharp:

| Conversation | Information |
|---|---|
| Chat (`#33`) | Documents |
| Threads / posts (`#36–#39`) | |
| VoidMail (`#34`) | |
| One-liners (`#32`) | |
| Mentions (`#35`) | (Mentions cross both surfaces) |

Conversation features stay as they are. Documents are new.

---

## 2. Data model

### 2.1 Schema

Single Flyway migration (V6 candidate):

```sql
CREATE TABLE documents (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug            TEXT NOT NULL UNIQUE,
  title           TEXT NOT NULL,
  kind            TEXT NOT NULL CHECK (kind IN
                    ('howto', 'article', 'link', 'glossary',
                     'release', 'note')),
  body            TEXT NOT NULL,
  frontmatter     JSONB NOT NULL DEFAULT '{}'::jsonb,
  tags            TEXT[] NOT NULL DEFAULT '{}'::text[],
  author_id       BIGINT NOT NULL REFERENCES users(id),
  visibility      TEXT NOT NULL DEFAULT 'public'
                    CHECK (visibility IN ('public', 'private')),
  status          TEXT NOT NULL DEFAULT 'published'
                    CHECK (status IN ('draft', 'pending', 'published')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  search_vector   tsvector,
  anchor_document_id UUID                                   -- ADR-024; null until indexed
);

CREATE INDEX documents_search ON documents USING GIN (search_vector);
CREATE INDEX documents_tags   ON documents USING GIN (tags);
CREATE INDEX documents_kind   ON documents (kind);
CREATE INDEX documents_author ON documents (author_id);
CREATE INDEX documents_status ON documents (status);
CREATE INDEX documents_updated ON documents (updated_at DESC);

-- Multi-author edits. Empty for solo-authored docs.
CREATE TABLE document_editors (
  document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  user_id     BIGINT NOT NULL REFERENCES users(id),
  PRIMARY KEY (document_id, user_id)
);

-- Cross-references (in-doc links to other docs). Maintained by a
-- save-time parse of the body.
CREATE TABLE document_links (
  source_id  BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  target_id  BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL DEFAULT 'reference',
  PRIMARY KEY (source_id, target_id, kind)
);
CREATE INDEX document_links_target ON document_links (target_id);

-- Edit history.
CREATE TABLE document_revisions (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  body        TEXT NOT NULL,
  frontmatter JSONB NOT NULL,
  edited_by   BIGINT NOT NULL REFERENCES users(id),
  edited_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX document_revisions_doc ON document_revisions (document_id, edited_at DESC);

-- Trigger to maintain search_vector on insert/update.
CREATE OR REPLACE FUNCTION documents_update_search_vector() RETURNS TRIGGER AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('simple', coalesce(NEW.title, '')), 'A') ||
    setweight(to_tsvector('simple', array_to_string(NEW.tags, ' ')), 'B') ||
    setweight(to_tsvector('simple', coalesce(NEW.body, '')), 'C');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER documents_search_vector
  BEFORE INSERT OR UPDATE OF title, tags, body
  ON documents FOR EACH ROW
  EXECUTE FUNCTION documents_update_search_vector();
```

Note: `simple` config (no stemming) is deliberate — a music-vocab
community shouldn't have "EBM" stemmed.

### 2.2 What's *not* in the schema

- **No `spaces` table.** A user's "personal space" is the predicate
  `?author=SYSOP`; it doesn't need its own row.
- **No `path` column, no `parent_id`.** Documents are not nested.
  Faceted navigation produces breadcrumbs from metadata; nothing in
  the schema enforces hierarchy.
- **No `embedding vector` column.** Semantic retrieval lives in
  Anchor (ADR-024); VOIDcore only stores the foreign key
  (`anchor_document_id`).
- **No `space_id`, no `folder_id`.** Same reason — sets, not
  containers.

### 2.3 Slug rules

- Globally unique across documents.
- Lowercase, alphanumeric, hyphens; auto-derived from title on
  create (`"Sidechain Tricks"` → `sidechain-tricks`); collisions
  resolved by appending `-N`.
- Editable by author after creation; old slug stays as a redirect
  in `frontmatter.aliases[]` so existing in-doc links don't break.
- Slugs appear in URLs (`?doc=sidechain-tricks`), in chat
  linkifiers (`~SYSOP/sidechain-tricks`), and in cross-references.

---

## 3. Kinds and frontmatter

`kind` is a closed set in v1; new kinds require a schema check
update. Each kind has an expected `frontmatter` shape — the
renderer assumes it but the database doesn't enforce it (JSONB).

### 3.1 `howto`

Step-by-step instructional content.

```jsonc
{
  "summary": "string — one-sentence overview",
  "prerequisites": ["string", ...],
  "outcome": "string — what the reader should be able to do after"
}
```

Body is markdown; renderer presents prerequisites as a checklist
above the body.

### 3.2 `article`

Long-form prose. Reviews, opinions, liner notes, sysop announcements.

```jsonc
{
  "summary": "string — abstract / TL;DR",
  "pinned": false                                 // optional; pinned articles surface on the main menu
}
```

Pinned articles take the role v1 announcements (`#26`) currently play.

### 3.3 `link`

A curated external resource. Body is the writeup; frontmatter holds
the URL.

```jsonc
{
  "url": "string — destination",
  "summary": "string — one line on what's there and why",
  "source_kind": "string — 'article' | 'video' | 'audio' | 'tool' | 'other'"
}
```

Renderer surfaces the URL prominently with `[O]pen` keystroke.

### 3.4 `glossary`

Term + definition + see-also. Body is the long-form definition.

```jsonc
{
  "term": "string — the term being defined",
  "see_also": ["slug", ...]                       // sibling glossary entries
}
```

`see_also` slugs auto-populate the link graph on save.

### 3.5 `release`

The current "files" recast. Music release catalog rows.

```jsonc
{
  "filename": "string — e.g. PATTERN9.ZIP (legacy)",
  "artist": "string",
  "year": 2024,
  "label": "string",
  "catalog_number": "string",
  "genre": "string",
  "external_url": "string — Bandcamp / SoundCloud / Spotify",
  "size_bytes": 8807042,
  "download_count": 412
}
```

Body is the NFO. The V5 metadata columns from `#81` migrate into
this frontmatter shape; the existing `files` table is dropped after
backfill (V6 migration handles this in one transaction).

### 3.6 `note`

Short-form annotation. The default kind for casual jotting. May
attach to another document via frontmatter:

```jsonc
{
  "anchor_doc_id": 42,                            // optional; "this is a note about doc 42"
  "summary": "string"                             // optional one-liner
}
```

Notes attached to other documents render under the parent in the
parent's viewer.

---

## 4. Faceted navigation

The core surface. Replaces folders, wikis, paths, and spaces as
organisational primitives.

### 4.1 Concept

The user enters an **info surface** showing the available facets
and recent documents. Selecting a facet narrows the visible set.
Multiple facets compose by intersection; order doesn't change the
result, only the breadcrumb display.

Facets exposed in v1:

| Facet | Source | Example values |
|---|---|---|
| `kind` | `documents.kind` | `howto`, `release` |
| `tag` | element of `documents.tags` | `samples`, `eurorack` |
| `author` | `documents.author_id` (joined to handle) | `SYSOP`, `captaincrunch` |
| `when` | `documents.updated_at` (year, month) | `2024`, `2024-10` |
| `status` | `documents.status` (sysop only) | `pending` |
| `has` | structural — `pinned`, `linked`, etc. | `pinned`, `has-anchor` (ADR-024) |
| `search` | full-text query against `search_vector` | textual |

A **breadcrumb** is the serialised current intersection:

```
INFO/kind=howto/tag=samples/by=SYSOP
```

This URL-style display is for the user, not for storage. Internally
the same set is represented as a structured filter object.

### 4.2 Surface

Default landing on `[I]nfo`:

```
== INFO ==                                        240 documents

  recent:
  [1] kick-drum-compression          SYSOP         2 days ago
  [2] industrial-glossary            SYSOP         3 days ago
  [3] clouds-patches                 captaincrunch 1 week ago
  [4] pattern-nine                   SYSOP         3 weeks ago
  [5] ...

  narrow by:
  [K] kind                          6 kinds
  [T] tag                         247 unique tags
  [B] by                           12 authors
  [W] when                          2023 / 2024 / 2026
  [/] search

  [N] new document    [Q] back to menu
```

`[K]ind` opens the kind facet picker:

```
== INFO/kind ==                  filter by kind

  [1] howto                     7
  [2] article                  12
  [3] link                      5
  [4] glossary                  3
  [5] release                  47
  [6] note                    166

  [..] back  [/] search  [Q] back to menu
```

`[1] howto` narrows the set:

```
== INFO/kind=howto ==                             7 documents

  [1] kick-drum-compression          SYSOP         2 days ago
  [2] sidechain-tricks               SYSOP         3 weeks ago
  [3] eurorack-patching-101          captaincrunch 5 weeks ago
  [4] ...

  narrow further:
  [T] tag                         14 tags within these
  [B] by                           3 authors within these
  [W] when                         2024 only

  [..] back  [/] search  [Q] back to menu
```

The **narrow further** suggestions are dynamic — only show facets
that would meaningfully cut the current set. If everything in
`kind=howto` is from 2024, `[W]hen` doesn't appear; one-value
facets aren't useful.

Selecting a numbered document opens its viewer (§5).

### 4.3 Default ordering

Within any faceted view, default sort is `updated_at DESC`. Sysop
can configure a per-view default; users can `[S]ort` between:

- `recent` (updated_at desc) — default
- `created` (created_at desc)
- `alpha` (title asc)
- `most-linked` (incoming `document_links` count desc) — for
  power-user discovery of "what's referenced most"

### 4.4 Breadcrumb canonicalisation

Two paths to the same set produce identical results. The
breadcrumb display order is **the order the user selected the
facets in**, preserved for orientation. The serialised filter is
canonical (alphabetical by facet name) for caching, intent
encoding, and deep linking.

`?info=kind=howto&tag=samples` is equivalent to
`?info=tag=samples&kind=howto`. Both render the same documents;
breadcrumb shows the order the URL specified.

### 4.5 Power-user filter syntax

Advanced users who hit `[/] search` can type filter expressions
directly:

```
kind:howto tag:samples by:SYSOP
```

Whitespace-separated `facet:value` pairs. Bare words are treated as
full-text search against `search_vector`. Negation via leading `-`:
`-tag:beta` excludes that tag. This is a power-user surface; the
default UI is point-and-click via menus.

---

## 5. Rendering

The same body field renders differently per kind. Each kind has a
small renderer; same protocol, different presentational frame.

### 5.1 Common header

Every doc viewer shows:

```
== <kind> · <title> ==                          by <author>, updated <when>

  <kind-specific header block — e.g. release metadata, glossary term, link URL>

  <body, rendered with the existing region linkifier>

  ──────────────────────────────────────────────────────
  tags: <tag1> <tag2> <tag3>          status: <published|draft|pending>
  
  <kind-specific footer — e.g. backlinks, see-also, attached notes>
  
  [E]dit (if you can)   [B]acklinks   [/] search   [Q] back
```

### 5.2 Per-kind variations

- **`howto`**: prerequisites checklist above body; outcome line
  below.
- **`article`**: body only; pinned articles get a `★` indicator in
  the header.
- **`link`**: `[O]pen` keystroke surfaces in the menu; URL
  prominent in header.
- **`glossary`**: term in larger ASCII letters as the title; `see
  also` slugs render as inline links.
- **`release`**: V5 metadata block (artist / year / label /
  catalog / genre) above the NFO body, exactly as in `#81`.
- **`note`**: minimal frame; if `anchor_doc_id` is set, link back
  to the parent.

### 5.3 The `[?]ask` keystroke

When `voidcore.anchor.enabled=true` AND the document has an
`anchor_document_id`, an extra keystroke `[?]ask` is offered. See
§9.

---

## 6. Cross-references and the link graph

Links between documents are first-class. They power backlinks
("what references this?") and provide the wiki-style discovery
benefit without committing to a wiki structure.

### 6.1 In-body link syntax

Inside a document body (or any `region` content), references to
other documents take one of two forms:

- `~slug` — bare slug reference. The renderer resolves it to a
  link if the slug exists.
- `~author-handle/slug` — qualified reference; useful in chat or
  when slugs collide. The first form is the normal case.

Examples:

```
see ~sidechain-tricks for the full pattern
~SYSOP/pattern-nine has more on this
```

These render as cyan underlined links (extending the existing URL
linkifier in `region.ts`).

### 6.2 Save-time link extraction

On document save, the body is parsed for `~slug` references. Each
match becomes a row in `document_links` with `kind='reference'`.
On re-save, the `document_links` rows for that source are replaced
in one transaction.

`see_also` slugs in glossary frontmatter are added as `kind='see-
also'` rows.

### 6.3 Backlinks view

`[B]acklinks` on any document viewer shows the list of incoming
references:

```
== BACKLINKS for ~kick-drum-compression ==        3 inbound

  [1] ~SYSOP/sidechain-tricks                     1 ref
  [2] ~captaincrunch/clouds-patches               1 ref
  [3] ~industrial-glossary                        1 ref (see-also)
```

Implementation is one indexed query against `document_links` —
O(1).

---

## 7. Editor model

### 7.1 Authoring

- `[N]ew` from any info surface or main menu opens the new-doc
  flow.
- Step 1: pick `kind` from a keystroke menu.
- Step 2: line prompt for `title`.
- Step 3: `tags` (space-separated; blank skips).
- Step 4: kind-specific frontmatter prompts.
- Step 5: body — multi-line input with the existing `\\n`
  separator-or-empty-line-saves pattern (matches VoidMail and the
  V5 NFO editor).
- Step 6: visibility — `[P]ublic` / `[P]rivate`.
- On save: row inserted with `status='published'`, search vector
  populated by trigger, link graph parsed.

### 7.2 Editing

- `[E]dit` on a viewer if `author_id == current_user OR
  current_user IN document_editors`.
- Kind-keyed sub-menu, same shape as the V5 file metadata editor:
  ```
  [T] title   [B] body   [F] frontmatter   [G] tags
  [V] visibility   [D] delete   [Q] save and exit
  ```
- Each letter walks one prompt and returns to this menu so the
  author can edit several fields in sequence.
- Save creates a `document_revisions` row.

### 7.3 Deletion

`[D]elete` on a viewer prompts for literal `DELETE` confirmation
(matches the V5 file-deletion flow). Cascades to revisions, links,
editor entries.

### 7.4 Sysop overrides

Sysop can edit / delete any document regardless of authorship.
Audit-logged via `sysop_actions` (existing pattern).

---

## 8. Search

### 8.1 Lexical (always available)

The `search_vector` column is queryable directly:

```sql
SELECT id, title, ts_headline('simple', body, query, 'StartSel=<<,StopSel=>>') AS snippet,
       ts_rank_cd(search_vector, query) AS rank
FROM documents, plainto_tsquery('simple', :q) query
WHERE search_vector @@ query
  AND (visibility = 'public' OR author_id = :user_id OR EXISTS (
    SELECT 1 FROM document_editors WHERE document_id = id AND user_id = :user_id))
ORDER BY rank DESC
LIMIT 50;
```

Surfaces:

- `[/]search` from any info view.
- Power-user filter syntax (§4.5) — bare words become a search
  facet automatically.

### 8.2 Semantic (when Anchor enabled)

When `voidcore.anchor.enabled=true`, semantic retrieval runs in
parallel:

```
search query
   │
   ├──► lexical channel (Postgres tsvector) → top 50
   └──► AnchorClient.retrieve(query) → top 50

       Reciprocal Rank Fusion (RRF, k=60 default)
                       │
                       ▼
                  top 20 results
```

Identical result schema either way (kind, title, snippet, score) —
the user doesn't see the channel boundary. Anchor unreachable →
silently fall back to lexical only.

### 8.3 What gets indexed

| Field | Lexical weight | In Anchor `/ingest` |
|---|---|---|
| `title` | A | Yes (as title) |
| `tags` | B | Yes (concatenated into ingest body) |
| `body` | C | Yes (primary content) |
| `frontmatter` | not indexed v1 | Yes (kind-specific extraction) |

Frontmatter could be lex-indexed later by extending the trigger;
deferred until needed.

---

## 9. The `[?]ask` interaction

Available only when `voidcore.anchor.enabled=true` AND the document
has been ingested into Anchor (`anchor_document_id IS NOT NULL`).

### 9.1 Surface

`[?]ask` keystroke on a document viewer prompts for a question:

```
ask ~sidechain-tricks: how does this compare to parallel compression?
```

### 9.2 Stream

VOIDcore calls `POST /documents/{anchor_id}/ask` (Anchor §5.4)
and subscribes to the SSE event stream. Each Anchor event becomes
a `region.update` to the user:

```
Anchor SSE event             → VOIDcore region update
─────────────────────────       ────────────────────────────
proposer.partial             → append to "proposer" sub-region
proposer.final               → fix proposer block, start critic
critic.partial               → append to "critic" sub-region
critic.final                 → fix critic, start synthesiser
synthesiser.partial          → append to "synthesiser" sub-region
synthesiser.final            → done; user can [Q]uit or [?]ask again
```

The terminal-aesthetic frame turns out to be a good fit: each
agent gets its own bordered block; the deliberation transcript
remains visible and scrollable.

### 9.3 Cancellation

`[Esc]` during the stream sends `DELETE /jobs/{id}` to Anchor and
returns to the document viewer. VOIDcore never blocks on a
hanging Anchor request.

### 9.4 Audit

`/ask` invocations land an `audit_events` row (lightweight — no
sysop-action gravity, just a usage record): user, anchor_doc_id,
question, response summary. Useful for "what are people asking
their docs about?" analytics later.

---

## 10. Migration

### 10.1 Recasting existing primitives

V6 migration runs in a single transaction:

```sql
-- 1. Move file rows into documents.
INSERT INTO documents (slug, title, kind, body, frontmatter,
                       tags, author_id, visibility, status, created_at)
SELECT
  lower(replace(filename, '.ZIP', '')),         -- slug
  title,
  'release',
  nfo_text,
  jsonb_build_object(
    'filename',       filename,
    'artist',         artist,
    'year',           year,
    'label',          label,
    'catalog_number', catalog_number,
    'genre',          genre,
    'external_url',   external_url,
    'size_bytes',     size_bytes,
    'download_count', download_count
  ),
  ARRAY[]::text[],                              -- tags blank, sysop curates later
  uploader_id,
  'public',
  'published',
  uploaded_at
FROM files;

-- 2. Move bulletins into documents.
INSERT INTO documents (slug, title, kind, body, frontmatter,
                       tags, author_id, visibility, status, created_at)
SELECT
  'bulletin-' || id,
  title,
  'article',
  body,
  jsonb_build_object('pinned', is_pinned),
  ARRAY[]::text[],
  author_id,
  'public',
  'published',
  posted_at
FROM bulletins;

-- 3. Drop the old tables.
DROP TABLE files;
DROP TABLE bulletins;
```

Old screen flows (`#27` file viewer, `#26` bulletins viewer) are
rewritten to render documents with the appropriate kind. Deep
links (`?nfo=PATTERN9` → `?doc=pattern-nine`) preserved via slug.

### 10.2 Tickets re-anchored

The polish tickets `#84-#93` are largely intact; their
definitions-of-done update against the documents substrate:

| Ticket | Re-anchor |
|---|---|
| `#84` user profiles | Default `?author=X` facet view; no separate "profile screen" primitive |
| `#85` what's new since last | Folds in `documents` updates as one of the kinds counted |
| `#90` full-text search | Becomes the §8.1 lexical channel; `#90` ticket renames accordingly |
| `#92` moderation queue | Operates on `documents.status='pending'` rows |
| `#27` / `#81` file area | Replaced by document `kind=release` (this spec, §3.5 + §10.1) |

`#86`, `#87`, `#88`, `#89`, `#91`, `#93` are unchanged — they're
about social / awareness / engagement features, not the
information substrate.

---

## 11. Open questions

These are deliberately unresolved; revisit per implementation PR.

- **Frontmatter validation.** v1 keeps it permissive (JSONB,
  unchecked). Per-kind JSON Schema validation could land later if
  authors start filing the wrong shape.
- **Slug aliases.** `frontmatter.aliases[]` redirects work for
  in-body links; do they also work for deep-link URLs from outside
  the BBS? Probably yes; needs spec wording.
- **Tag namespace.** Free-form is correct for v1. If tag sprawl
  becomes a problem, sysop tools could surface "rename tag X to Y
  globally" as a one-shot.
- **Document size limits.** Soft cap at, say, 64KB body / 1KB
  frontmatter / 50 tags? Worth picking a number before we discover
  the limit by accident.
- **Per-document discussion.** Threads attach to a document?
  v1 says no — keep documents and conversation separate. v1.6 might
  reconsider.
- **Drafts and autosave.** v1: documents are atomically saved on
  the editor's `[Q]save and exit`. v1.6 might add `status='draft'`
  with autosave-as-the-user-types.
- **Public / unlisted / private.** v1 ships only `public` and
  `private`. `unlisted` (accessible via direct slug, not surfaced
  in facets) is a clear v1.6 add when first useful.

---

## 12. Glossary

- **Document** — A typed, structured, queryable piece of curated
  information. The substrate of VOIDcore.
- **Kind** — One of `howto`, `article`, `link`, `glossary`,
  `release`, `note`. Drives the renderer and expected frontmatter
  shape.
- **Frontmatter** — JSONB column carrying kind-specific structured
  fields. Opaque to the database, schema-validated by the
  renderer.
- **Facet** — A metadata axis along which documents can be
  filtered (kind, tag, author, when, status, search). Selecting
  facet values defines a set of documents.
- **Faceted navigation** — The act of intersecting sets defined by
  facet selections. Replaces folders/wikis/spaces as the
  organisational primitive.
- **Set** — The current intersection of selected facets. The
  collection of documents visible at any point in the navigation.
- **Breadcrumb** — A serialised, ordered display of the user's
  current facet selections. Display only; identical sets reachable
  via different breadcrumb orders.
- **Backlinks** — The set of documents that reference a given
  document via in-body `~slug` links.
- **Connective tissue** — Conversation primitives (chat, threads,
  VoidMail, one-liners, mentions) that surround the information
  substrate without being part of it.
- **Anchor** — Sibling project (see ADR-024) providing optional
  semantic retrieval and document-as-agent deliberation. VOIDcore
  integrates with it via HTTP when `voidcore.anchor.enabled=true`.
