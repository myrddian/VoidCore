# MIGRATION — Document Substrate: V10 (typed schemas, revisions, soft delete)

**Status:** Plan, ready to execute
**Predecessor:** V6 (`db/migration/V6__documents_substrate.sql`) shipped the flat-enum
documents table. This migration evolves it into a typed-schemas substrate.
**Successor (planned):** V11 will drop legacy `files` / `bulletins` tables once all
consumers have migrated through this work.
**Companion specs:** `SPEC.md`, `SPEC-documents.md` (existing draft, partially superseded
by this plan), `AGENTS.md`.

---

## Table of contents

1. [Why this migration](#1-why-this-migration)
2. [What V6 actually shipped](#2-what-v6-actually-shipped)
3. [Target architecture](#3-target-architecture)
4. [V10 SQL — full DDL and backfill](#4-v10-sql--full-ddl-and-backfill)
5. [Built-in schemas seeded by V10](#5-built-in-schemas-seeded-by-v10)
6. [Application-layer changes](#6-application-layer-changes)
7. [Save / delete / restore flow](#7-save--delete--restore-flow)
8. [JSON Schema validation](#8-json-schema-validation)
9. [Consumer migration — files & bulletins](#9-consumer-migration--files--bulletins)
10. [Instance overlay deliverables](#10-instance-overlay-deliverables)
11. [Federation hooks (forward design only)](#11-federation-hooks-forward-design-only)
12. [Phased delivery plan](#12-phased-delivery-plan)
13. [Verification & test plan](#13-verification--test-plan)
14. [Open decisions](#14-open-decisions)
15. [Out of scope](#15-out-of-scope)
16. [File reference index](#16-file-reference-index)

---

## 1. Why this migration

V6 established the documents substrate as a single global pool with a fixed list of
six kinds (`howto`, `article`, `link`, `glossary`, `release`, `note`) hardcoded as a
SQL `CHECK` constraint and a Java `DocumentKind` enum. Frontmatter is an opaque
`JSONB` blob; per-kind shapes live in `FrontmatterSchema.java` (a static `EnumMap`).
There is no schema validation, no document revision counter, no soft delete, and the
existing `document_revisions` table is too thin to faithfully archive an edit history.

This migration does four things:

1. **Promotes types to data.** The fixed `kind` enum becomes a foreign key into a
   new `schemas` table. Sysops or the instance overlay can introduce new types
   (`release`, `campaign`, `build`, whatever a community needs) without forking
   core or shipping a Flyway migration.
2. **Versions schemas.** Schema definitions are immutable rows; bumping a schema
   adds a new row. Documents pin to the specific `(slug, version)` they were last
   saved against, so an old revision validates and renders correctly years later.
3. **Adds a real revision counter and full-snapshot archive.** Every `documents` row
   carries `rev`. Saves snapshot the prior state into `document_revisions` with all
   fields, not just body/frontmatter. Deletions write a final terminal revision and
   set `deleted_at`. Nothing is ever lost.
4. **Validates frontmatter against the schema at save time.** Per-document
   frontmatter must conform to the JSON Schema declared by its referenced
   `(type_slug, type_version)` row.

This is the structural prerequisite for the platform thesis: VOIDcore is a typed-
document substrate where operators define their own information shapes. Without
typed-schemas-as-data, every new type requires engine code; with this migration in
place, the instance overlay can declare `release` purely as overlay data, and other
deployments can do the same for their own scenes.

## 2. What V6 actually shipped

For exact context (reading
`/app/src/main/resources/db/migration/V6__documents_substrate.sql`):

**Tables created:**

- `documents` — `id, slug UNIQUE, title, kind TEXT CHECK(...), body, frontmatter JSONB,
  tags TEXT[], author_id, visibility, status, created_at, updated_at, search_vector,
  anchor_document_id UUID`. Six indexes including a GIN on `search_vector`.
- `document_editors (document_id, user_id)` — multi-author support.
- `document_links (source_id, target_id, kind)` — cross-reference graph.
- `document_revisions (id, document_id, body, frontmatter, edited_by, edited_at)` —
  thin; no rev counter, no title/tags/visibility/status/type, no edit summary, no
  deletion marker.

**Trigger:** `documents_search_vector` maintains `search_vector` BEFORE INSERT OR
UPDATE OF (title, tags, body), using `simple` config (no stemming — deliberate, see
SPEC-documents.md §2.1).

**Backfill:** 7 rows from `files` (kind=release, slug from filename, frontmatter
captures artist/year/label/catalog_number/genre/external_url/size_bytes/download_count).
3 rows from `bulletins` (kind=article, slug=`bulletin-{id}`, frontmatter captures
pinned). Sentinel sysop is created if no real sysop exists; `SysopBootstrap.java`
rewrites it on boot.

**Java surface (read-only at PR-2):**

- `io.aeyer.voidcore.documents.DocumentRow` — record with all columns except
  search_vector
- `io.aeyer.voidcore.documents.DocumentKind` — enum with the six values
- `io.aeyer.voidcore.documents.Visibility`, `Status` — enums
- `io.aeyer.voidcore.documents.DocumentRepository` — read-side jOOQ
- `io.aeyer.voidcore.documents.FrontmatterSchema` — static EnumMap of per-kind fields
- `io.aeyer.voidcore.documents.FrontmatterField` — `(letter, key, label, type)` record
- `DocumentScreen`, `DocsHubScreen`, `DocsFacet*Screen`, `DocsResultsScreen`,
  `DocsBacklinksScreen` — read-side UI

**Still using legacy tables:** `FilesListScreen`, `FileViewScreen`, sysop file CRUD
screens still write to `files`. `BulletinsListScreen`, `BulletinViewScreen`, sysop
bulletin screens still write to `bulletins`. Those tables are intact and feed the
backfill on first run; the long-term plan is to migrate consumers to read/write
through `documents` and drop the legacy tables.

**Latest applied migration:** V9 (`V9__app_state.sql`). Next available version is
**V10**.

## 3. Target architecture

Three tables compose the substrate after V10:

```
schemas                              -- type definitions, versioned, immutable rows
├── slug + version (composite identity)
├── definition JSONB                 -- JSON Schema for frontmatter validation
├── presentation JSONB               -- list-view fields, sort defaults, facets
└── status DRAFT|ACTIVE|DEPRECATED   -- new versions deprecate old, never delete

documents                            -- one row per live document; current state
├── (existing V6 columns retained)
├── type_slug + type_version (was kind, now FK to schemas)
├── rev INT                          -- monotonic per-document save counter
├── deleted_at, deleted_by           -- soft delete
└── frontmatter JSONB                -- validated against schemas[type_slug,type_version].definition

document_revisions                   -- archive of past states, including final state of deleted docs
├── (full snapshot of documents row at the moment of save)
├── rev                              -- which revision this is
├── edit_summary                     -- optional commit-message-like field
└── is_deletion BOOLEAN              -- true if this revision captures a deletion event
```

### Key design choices

**Markdown body, typed metadata.** Every VOIDcore document is a Markdown
document with typed metadata. Schemas define frontmatter shape, validation, and
presentation hints; they do not replace the core body model with per-type
storage engines or alternate content formats.

**Same-row JSONB for frontmatter, not a separate `extended_types` table.**
`documents.frontmatter` already exists and works; per-document validation happens at
the application layer against the referenced schema. Postgres GIN indexes on JSONB
paths give efficient queries over typed metadata.

**Schemas as immutable versioned rows.** Adding a new version of `release` means
inserting a new row with `(slug='release', version=current+1)`. Existing documents
remain pinned to their saved-against version. The "current" version is the highest
`status='active'` row per slug.

**Document `rev` is monotonic save counter; revisions table holds prior states.**
Convention: `documents.rev` reflects how many save events have occurred (initial
create = rev 1). `document_revisions[N]` is the state captured *just before* the
N+1th save — i.e., what the document looked like at rev N. Conv B from earlier
discussion.

**Soft delete via `deleted_at`.** Live queries filter `WHERE deleted_at IS NULL`.
Sysop tooling can list / restore deleted docs. Permanent purge is a separate sysop
operation; rare; out of scope here.

**Publish-creates-revision policy.** Drafts mutate the live row in place; the
moment a document transitions to `status='published'` (or any save while already
published) is what creates a revision. Tracked in §7.

**Document links are part of the core substrate.** Markdown bodies may contain
first-class links to other documents. On save, VOIDcore parses the body into the
`document_links` graph, enabling backlinks, graph-aware navigation, and
wiki-style knowledge building. The link graph is part of the platform, not an
overlay customisation.

**`kind` column renamed to `type_slug`.** Single rename keeps the data; the FK
constraint replaces the old CHECK. `DocumentKind` enum stays in Java as a typed
reference to the six built-in slugs but is no longer the canonical wire identifier.

## 4. V10 SQL — full DDL and backfill

Path: `app/src/main/resources/db/migration/V10__document_substrate_typed.sql`

```sql
-- =============================================================================
-- V10 — Typed schemas, revision counter, soft delete, full-snapshot revisions.
--
-- Promotes V6's fixed-enum kinds to data-driven schemas, adds a monotonic
-- revision counter and soft-delete columns to documents, and expands
-- document_revisions to a full snapshot of the row at the moment of save
-- (including a flag for deletion events).
--
-- Frontmatter validation against schemas.definition (JSON Schema) happens at
-- the application layer — see DocumentRepository / SchemaRepository changes
-- documented in MIGRATION-document-substrate.md §6.
-- =============================================================================

-- 1. schemas table -----------------------------------------------------------

CREATE TABLE schemas (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug         TEXT NOT NULL,
  version      INTEGER NOT NULL CHECK (version >= 1),
  label        TEXT NOT NULL,
  description  TEXT,
  definition   JSONB NOT NULL,           -- JSON Schema (draft 2020-12)
  presentation JSONB NOT NULL DEFAULT '{}'::jsonb,
                                         -- { list_columns: [...], sort_default: '-updated_at',
                                         --   facets: ['year','genre'], ... }
  status       TEXT NOT NULL DEFAULT 'active'
                 CHECK (status IN ('draft','active','deprecated')),
  created_by   BIGINT REFERENCES users(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (slug, version)
);

CREATE INDEX schemas_slug_active
  ON schemas (slug, version DESC)
  WHERE status = 'active';

-- 2. seed built-in schemas (note, article, howto, link, glossary, release) ---
--    All at version 1, status='active'. Definitions are JSON Schema draft 2020-12.
--    See §5 of MIGRATION-document-substrate.md for the full content of each.
--    (Definitions stored in this migration as JSONB literals for atomicity.)

INSERT INTO schemas (slug, version, label, description, definition, presentation, status)
VALUES
  ('note', 1, 'Note', 'A short personal observation, link, or fragment.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"summary":{"type":"string","maxLength":500},"anchor_doc_id":{"type":"string","format":"uuid"}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","author","updated_at"],"sort_default":"-updated_at"}'::jsonb,
    'active'),
  ('article', 1, 'Article', 'Long-form writing — opinion, essay, announcement.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"summary":{"type":"string","maxLength":500},"pinned":{"type":"boolean","default":false}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","author","updated_at"],"sort_default":"-updated_at","sort_pinned_first":true}'::jsonb,
    'active'),
  ('howto', 1, 'How-To', 'Procedural write-up — steps, prerequisites, expected outcome.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","properties":{"summary":{"type":"string","maxLength":500},"prerequisites":{"type":"array","items":{"type":"string"}},"outcome":{"type":"string","maxLength":500}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","author","updated_at"],"sort_default":"-updated_at"}'::jsonb,
    'active'),
  ('link', 1, 'Link', 'A pointer to an external resource with curated context.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["url"],"properties":{"url":{"type":"string","format":"uri"},"summary":{"type":"string","maxLength":500},"source_kind":{"type":"string","maxLength":40}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","source_kind","updated_at"],"sort_default":"-updated_at"}'::jsonb,
    'active'),
  ('glossary', 1, 'Glossary entry', 'A defined term with optional cross-references.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["term"],"properties":{"term":{"type":"string","maxLength":120},"see_also":{"type":"array","items":{"type":"string"}}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","term","updated_at"],"sort_default":"title"}'::jsonb,
    'active'),
  ('release', 1, 'Release', 'A published work — track, album, EP — with rich metadata.',
    '{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","required":["artist"],"properties":{"filename":{"type":"string"},"artist":{"type":"string","maxLength":120},"year":{"type":"integer","minimum":1900,"maximum":2100},"label":{"type":"string","maxLength":120},"catalog_number":{"type":"string","maxLength":60},"genre":{"type":"string","maxLength":60},"external_url":{"type":"string","format":"uri"},"size_bytes":{"type":"integer","minimum":0},"download_count":{"type":"integer","minimum":0}},"additionalProperties":false}'::jsonb,
    '{"list_columns":["title","artist","year","label"],"sort_default":"-year","facets":["genre","year","label"]}'::jsonb,
    'active');

-- 3. documents — rename kind, add type_version + rev + soft delete columns ---

-- 3a. Drop the existing CHECK constraint on kind (it's named by Postgres).
ALTER TABLE documents
  DROP CONSTRAINT documents_kind_check;

-- 3b. Rename kind → type_slug. Existing data preserved unchanged.
ALTER TABLE documents
  RENAME COLUMN kind TO type_slug;

-- 3c. Drop the old idx (it's now named after the old column; recreate cleanly).
DROP INDEX IF EXISTS documents_kind;
CREATE INDEX documents_type_slug ON documents (type_slug);

-- 3d. Add the supporting columns. Defaults make the migration safe to run on
--     a populated database; backfill below sets the right values.
ALTER TABLE documents
  ADD COLUMN type_version INTEGER NOT NULL DEFAULT 1
    CHECK (type_version >= 1),
  ADD COLUMN rev          INTEGER NOT NULL DEFAULT 1
    CHECK (rev >= 1),
  ADD COLUMN deleted_at   TIMESTAMPTZ,
  ADD COLUMN deleted_by   BIGINT REFERENCES users(id);

-- 3e. Add the FK from (type_slug, type_version) to schemas (slug, version).
--     Done after the seed in step 2 so existing rows reference valid schema rows.
ALTER TABLE documents
  ADD CONSTRAINT documents_type_fk
    FOREIGN KEY (type_slug, type_version)
    REFERENCES schemas (slug, version);

-- 3f. Indexes for the new columns.
CREATE INDEX documents_live    ON documents (type_slug) WHERE deleted_at IS NULL;
CREATE INDEX documents_deleted ON documents (deleted_at) WHERE deleted_at IS NOT NULL;

-- 4. document_revisions — expand to full snapshot ----------------------------

-- 4a. Add the new columns. Most are NOT NULL after backfill; we add them
--     nullable, populate, then enforce.
ALTER TABLE document_revisions
  ADD COLUMN rev           INTEGER,
  ADD COLUMN title         TEXT,
  ADD COLUMN tags          TEXT[],
  ADD COLUMN visibility    TEXT,
  ADD COLUMN status        TEXT,
  ADD COLUMN type_slug     TEXT,
  ADD COLUMN type_version  INTEGER,
  ADD COLUMN edit_summary  TEXT,
  ADD COLUMN is_deletion   BOOLEAN NOT NULL DEFAULT false;

-- 4b. Backfill rev numbers — existing revisions get rev=1,2,... per document
--     ordered by edited_at. (V6 ships with no revisions written yet, so this
--     is a no-op on a freshly-migrated DB; included for safety on databases
--     that have accumulated revisions before V10.)
WITH numbered AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY document_id
           ORDER BY edited_at, id
         ) AS rn
    FROM document_revisions
)
UPDATE document_revisions dr
   SET rev = numbered.rn
  FROM numbered
 WHERE dr.id = numbered.id;

-- 4c. Backfill the snapshot fields from current document state. We don't have
--     true historical values for pre-V10 revisions; we accept this loss of
--     fidelity — pre-migration revisions show today's title/tags/etc rather
--     than whatever they were at edit time. Documented behaviour.
UPDATE document_revisions dr
   SET title        = d.title,
       tags         = d.tags,
       visibility   = d.visibility,
       status       = d.status,
       type_slug    = d.type_slug,
       type_version = d.type_version
  FROM documents d
 WHERE dr.document_id = d.id;

-- 4d. Promote backfilled columns to NOT NULL.
ALTER TABLE document_revisions
  ALTER COLUMN rev          SET NOT NULL,
  ALTER COLUMN title        SET NOT NULL,
  ALTER COLUMN tags         SET NOT NULL,
  ALTER COLUMN visibility   SET NOT NULL,
  ALTER COLUMN status       SET NOT NULL,
  ALTER COLUMN type_slug    SET NOT NULL,
  ALTER COLUMN type_version SET NOT NULL;

-- 4e. Uniqueness: only one revision row per (document, rev).
ALTER TABLE document_revisions
  ADD CONSTRAINT document_revisions_doc_rev_unique
    UNIQUE (document_id, rev);

-- 4f. FK from revisions to schemas — same shape as documents.
ALTER TABLE document_revisions
  ADD CONSTRAINT document_revisions_type_fk
    FOREIGN KEY (type_slug, type_version)
    REFERENCES schemas (slug, version);

-- 4g. Index for "find revisions of doc X in order" queries.
DROP INDEX IF EXISTS document_revisions_doc;
CREATE INDEX document_revisions_doc
  ON document_revisions (document_id, rev DESC);

CREATE INDEX document_revisions_deletions
  ON document_revisions (document_id)
  WHERE is_deletion = true;

-- 5. Backfill documents.rev --------------------------------------------------
--    Set rev to (count of existing revisions for this doc) + 1. On a fresh DB
--    this leaves all docs at rev=1; on an aged DB with prior revisions it sets
--    each doc to its proper monotonic position.

UPDATE documents d
   SET rev = COALESCE(
     (SELECT COUNT(*) FROM document_revisions dr WHERE dr.document_id = d.id),
     0
   ) + 1;
```

### Notes on the SQL

- **No new trigger added.** The existing `documents_update_search_vector` trigger
  continues to fire on title/tags/body changes; rename of `kind` → `type_slug`
  doesn't affect it.
- **Schema seed lives in this migration deliberately.** The six built-ins must
  exist before the `documents_type_fk` FK is added (step 3e), and the seed is
  small enough to be a single self-contained migration.
- **CHECK constraint name `documents_kind_check`** is the Postgres-default name
  for the unnamed CHECK in V6's CREATE TABLE. If a different Postgres version
  generates a different name, this migration may need tweaking — verify with
  `\d+ documents` in psql before running on a live database.
- **`type_version DEFAULT 1`** is the ratchet: existing rows all become
  version 1 against the just-seeded schemas. New documents must explicitly set
  the version, but the application layer always sets it to the current active
  version automatically (see §7).

## 5. Built-in schemas seeded by V10

V10 ships six built-in schemas at version 1, all with `status='active'`. Their
JSON Schema definitions and presentation hints are embedded in the migration SQL
(§4 step 2). For readability and easier review, here are the same definitions in
unindented form:

### `note` (v1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "summary":       { "type": "string", "maxLength": 500 },
    "anchor_doc_id": { "type": "string", "format": "uuid" }
  },
  "additionalProperties": false
}
```

### `article` (v1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "summary": { "type": "string", "maxLength": 500 },
    "pinned":  { "type": "boolean", "default": false }
  },
  "additionalProperties": false
}
```

### `howto` (v1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "summary":       { "type": "string", "maxLength": 500 },
    "prerequisites": { "type": "array",  "items": { "type": "string" } },
    "outcome":       { "type": "string", "maxLength": 500 }
  },
  "additionalProperties": false
}
```

### `link` (v1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["url"],
  "properties": {
    "url":         { "type": "string", "format": "uri" },
    "summary":     { "type": "string", "maxLength": 500 },
    "source_kind": { "type": "string", "maxLength": 40 }
  },
  "additionalProperties": false
}
```

### `glossary` (v1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["term"],
  "properties": {
    "term":     { "type": "string", "maxLength": 120 },
    "see_also": { "type": "array",  "items": { "type": "string" } }
  },
  "additionalProperties": false
}
```

### `release` (v1)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["artist"],
  "properties": {
    "filename":       { "type": "string" },
    "artist":         { "type": "string",  "maxLength": 120 },
    "year":           { "type": "integer", "minimum": 1900, "maximum": 2100 },
    "label":          { "type": "string",  "maxLength": 120 },
    "catalog_number": { "type": "string",  "maxLength": 60 },
    "genre":          { "type": "string",  "maxLength": 60 },
    "external_url":   { "type": "string",  "format": "uri" },
    "size_bytes":     { "type": "integer", "minimum": 0 },
    "download_count": { "type": "integer", "minimum": 0 }
  },
  "additionalProperties": false
}
```

### Presentation hints

Each schema's `presentation` JSONB carries UI hints for screens that browse
documents of that type:

| Field           | Meaning                                                          |
|-----------------|------------------------------------------------------------------|
| `list_columns`  | Column ids to surface in list views (in order)                   |
| `sort_default`  | Default sort key, prefixed with `-` for descending               |
| `sort_pinned_first` | If true, pinned items float to the top before sort applies   |
| `facets`        | Frontmatter keys that should be exposed as faceted-narrow facets |

These are advisory — screens may override them — but they let a brand-new typed
screen render usefully against any schema with zero per-type code, which is the
cornerstone of the platform thesis.

### A note on `release` shipping in core

The release schema lives in V10 because its presence is required for the V6
backfill rows to satisfy the FK in step 3e. The seven backfilled `release`
documents reference schemas where `slug='release', version=1` — they have to find
that row, so it must be seeded by the same migration that adds the FK.

In the open-source split (the planned `voidcore` repo), the picture is different:
VoidCore core ships a clean V6 with **no** backfilled releases, so its V10 does
not need `release` as a built-in. `release` moves to the instance overlay's
`R__instance_types.sql` as the canonical example of an overlay-defined type. See §10.

So within this repo, `release` is best understood as a transitional built-in: it
exists here because the pre-split migration chain and backfill depend on it, not
because public VOIDcore should permanently treat music-release catalogues as a
core primitive.

## 6. Application-layer changes

### 6.1 New file: `Schema.java` and `SchemaRepository.java`

Path: `app/src/main/java/io/aeyer/voidcore/documents/Schema.java`

```java
package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** A row in the schemas table. Immutable; new schema versions are new rows. */
public record Schema(
        long id,
        String slug,
        int version,
        String label,
        String description,
        JsonNode definition,    // JSON Schema document
        JsonNode presentation,  // {list_columns, sort_default, facets, ...}
        SchemaStatus status,
        Long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
```

Path: `app/src/main/java/io/aeyer/voidcore/documents/SchemaStatus.java`

```java
package io.aeyer.voidcore.documents;

public enum SchemaStatus {
    DRAFT, ACTIVE, DEPRECATED;
    public String wireValue() { return name().toLowerCase(); }
    public static SchemaStatus parse(String s) {
        return SchemaStatus.valueOf(s.toUpperCase());
    }
}
```

Path: `app/src/main/java/io/aeyer/voidcore/documents/SchemaRepository.java`

Read-side methods needed at minimum:

- `Optional<Schema> findActive(String slug)` — returns the highest-version active
  schema for a given slug. Used by every save to determine `type_version` to pin
  to.
- `Optional<Schema> find(String slug, int version)` — exact lookup. Used when
  loading a document to fetch the schema row it pins to.
- `List<Schema> listActive()` — used by Sysop UIs and DocsHub to enumerate
  available types.
- `List<Schema> listVersions(String slug)` — full version history of a schema.

Write-side methods (sysop-only, can be deferred to v2 sysop UI work):

- `Schema insertVersion(String slug, JsonNode definition, JsonNode presentation,
  String label, String description, long createdBy)` — adds a new
  `(slug, version+1)` row with `status='draft'`. Promotes prior active to
  deprecated if/when the new one is set active.
- `void setStatus(long schemaId, SchemaStatus newStatus)` — promote/deprecate.

### 6.2 Modified: `DocumentRow.java`

The record gains the new fields:

```java
public record DocumentRow(
        long id,
        String slug,
        String title,
        String typeSlug,        // was DocumentKind kind
        int typeVersion,        // NEW
        int rev,                // NEW
        String body,
        JsonNode frontmatter,
        List<String> tags,
        long authorId,
        Visibility visibility,
        Status status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt,   // NEW; null = live
        Long deletedBy,             // NEW; null = live
        UUID anchorDocumentId
) {
    public boolean isDeleted() { return deletedAt != null; }
}
```

### 6.3 Status of `DocumentKind.java` enum

Two paths considered:

1. **Delete the enum.** Replace every reference with the `String typeSlug`. Simpler
   long-term; matches the platform thesis (types are data, not code).
2. **Keep the enum, narrow its purpose.** Rename to `BuiltinType` and treat it as
   a typed reference to the six known-by-name slugs. Still useful for switch
   statements in built-in renderers. Custom types use string slugs directly.

**Recommendation: option 2** for v1 — minimal disruption, lets renderer code stay
typed for the common case, and is honest about the fact that the six built-ins
are real and will be in core for a long time. Migrate to option 1 (full string
typing) over time as plugin types become commonplace.

```java
package io.aeyer.voidcore.documents;

/** Built-in types known to voidcore. Custom (operator-defined) types use
 *  raw slugs, not enum values. Use {@link DocumentRow#typeSlug()} as the
 *  canonical identifier; this enum is a convenience for built-in handlers. */
public enum BuiltinType {
    NOTE("note"),
    ARTICLE("article"),
    HOWTO("howto"),
    LINK("link"),
    GLOSSARY("glossary"),
    RELEASE("release");

    private final String slug;
    BuiltinType(String slug) { this.slug = slug; }
    public String slug() { return slug; }

    public static java.util.Optional<BuiltinType> bySlug(String slug) {
        for (var t : values()) if (t.slug.equals(slug)) return java.util.Optional.of(t);
        return java.util.Optional.empty();
    }
}
```

Existing `DocumentKind` references in code get rewritten to `BuiltinType.bySlug(row.typeSlug()).orElse(...)` with a sensible fallback for unknown types (probably "render generically" rather than crashing).

Whether the transitional enum keeps the name `DocumentKind` or becomes
`BuiltinType`, its role should shrink to "convenience for built-in handlers"
rather than "source of truth for what document types exist." The canonical type
identity is `(type_slug, type_version)` from the database; no new engine feature
should require adding enum values in order to introduce a new document type.

### 6.4 `FrontmatterSchema.java` — deprecate

The static EnumMap-based schema in this file is obviated by data-driven schemas.
v1 path: keep the file as a fallback for built-in types, mark it `@Deprecated`,
and change consumers to consult `SchemaRepository.findActive(typeSlug)` first,
falling back to `FrontmatterSchema.fieldsFor(BuiltinType)` only when the schema
table is unreachable (which shouldn't happen in normal operation).

v1.x path: delete the file entirely once all consumers go through
SchemaRepository.

The `FrontmatterField` record stays — it's still a useful runtime structure for
rendering the per-kind keystroke menu. It now gets populated from
`schemas.presentation` rather than from hardcoded Java.

### 6.5 jOOQ regeneration

The migration changes column types and adds a new table. Regenerate jOOQ:

```bash
./scripts/regenerate-jooq.sh
```

The script applies V1–V10 in sequence in a throwaway Postgres container, then
runs codegen. Inspect `app/src/jooq/java/io/aeyer/voidcore/jooq/tables/Documents.java`
and `Schemas.java` after regeneration to confirm the new columns appear with
correct types. `regenerate-jooq.sh` already handles the V6 sentinel-sysop quirk;
no change needed to the script itself.

### 6.6 Flyway location config

V10 lives in the standard `classpath:db/migration` location. No change to
`app/src/main/resources/application.yml` is required for this migration alone.

The future overlay-supports-extra-migrations work (where `R__instance_release_v2.sql`
or similar lives in an overlay-mounted directory) will require updating
`spring.flyway.locations` to a comma-separated list including
`filesystem:/instance/migrations`. That work is part of the OSS split plan
(§9 of the existing plan), not this migration.

## 7. Save / delete / restore flow

### 7.1 Save (publish-creates-revision policy)

When a document transitions to `status='published'`, OR when a save happens on an
already-published document, write a revision:

```
DocumentRepository.save(DocumentRow next, long editorId, String editSummary):

  IF next.id == 0 (new):
    schema = SchemaRepository.findActive(next.typeSlug)
              .orElseThrow(UnknownTypeException)
    next.typeVersion = schema.version
    validate(next.frontmatter, schema.definition)
    next.rev = 1
    return INSERT documents (next)        -- no revision row at create time

  ELSE (existing):
    current = repo.findById(next.id).orElseThrow(NotFoundException)
    schema = SchemaRepository.find(next.typeSlug, next.typeVersion)
              .orElseGet(() -> SchemaRepository.findActive(next.typeSlug).orElseThrow())
    -- A document can be re-pinned to a newer schema version when saved; if the
    -- caller didn't bump it explicitly we stay on the same version (no implicit
    -- migration). The caller is the editor logic; tooling for explicit bumps
    -- lives in §12 phase 4.
    validate(next.frontmatter, schema.definition)

    -- Snapshot prior state into revisions ONLY if the prior state was published
    -- (publish-creates-revision policy). Drafts mutate in place.
    IF current.status == PUBLISHED:
      INSERT INTO document_revisions (
        document_id, rev, title, body, frontmatter, type_slug, type_version,
        tags, visibility, status, edited_by, edit_summary, is_deletion
      ) VALUES (
        current.id, current.rev, current.title, current.body, current.frontmatter,
        current.typeSlug, current.typeVersion, current.tags, current.visibility,
        current.status, editorId, editSummary, false
      )
      next.rev = current.rev + 1
    ELSE:
      next.rev = current.rev   -- draft mutation, no revision created

    UPDATE documents SET (..., rev = next.rev, updated_at = now()) WHERE id = next.id

    IF next.body != current.body:
      rewrite document_links for next.id against next.body
```

The link-graph rewrite is part of the save contract, not a best-effort side
effect. `document_links` should always reflect the current Markdown body of the
live row. Revision snapshots preserve historical body/frontmatter state; the
graph remains a projection of the current document text.

### 7.2 Delete (soft delete, terminal revision)

```
DocumentRepository.delete(long docId, long deleterId, String reason):
  current = repo.findById(docId).orElseThrow(NotFoundException)
  IF current.deletedAt != null:
    return  -- already deleted, no-op

  -- Always write a terminal revision on deletion, regardless of draft/published.
  INSERT INTO document_revisions (
    ..., rev = current.rev, edit_summary = reason, is_deletion = true
  )
  UPDATE documents
     SET deleted_at = now(),
         deleted_by = deleterId,
         rev = rev + 1
   WHERE id = docId
```

### 7.3 Restore (undo soft delete)

```
DocumentRepository.restore(long docId, long restorerId):
  current = repo.findByIdIncludingDeleted(docId).orElseThrow(NotFoundException)
  IF current.deletedAt == null:
    return  -- already live, no-op

  -- Optionally write a "restored" revision so audit trail is complete.
  INSERT INTO document_revisions (
    ..., rev = current.rev, edit_summary = 'restored', is_deletion = false
  )
  UPDATE documents
     SET deleted_at = NULL,
         deleted_by = NULL,
         rev = rev + 1
   WHERE id = docId
```

### 7.4 Listing — live vs. all

Standard reads: `WHERE deleted_at IS NULL` (the `documents_live` partial index
makes this efficient).

Sysop "deleted documents" view: `WHERE deleted_at IS NOT NULL` (the
`documents_deleted` partial index supports this).

Full audit / forensics: omit the filter entirely; rare, no index needed.

### 7.5 Schema version pinning across save

Convention: when saving an existing document, the application defaults to keeping
the same `type_version` it was last saved with. Bumping a document to a newer
schema version is an explicit action — sysop tooling, not an automatic migration.
This guarantees that an old document survives schema evolution without forced
data migration; it stays valid against the version it pins to (which still
exists in the schemas table because we never delete schema rows).

When the application loads a document and finds its pinned schema version is
deprecated, it can surface a warning to the editor ("this document uses release
v1; v2 is now active") without forcing the bump. The editor can choose to migrate.

## 8. JSON Schema validation

V10 introduces server-side validation of `documents.frontmatter` against the
referenced schema. Validation happens at the application layer (the database
stores any well-formed JSONB; the engine refuses to save invalid documents).

### 8.1 Library choice

Two mature Java JSON Schema validators on the table; both support draft 2020-12:

| Library | Maven coords | License | Notes |
|---|---|---|---|
| **networknt/json-schema-validator** | `com.networknt:json-schema-validator:1.5.x` | Apache-2.0 | Active. Lightweight. Fast. Good error messages. **Recommended.** |
| everit-org/json-schema | `com.github.erosb:everit-json-schema:1.x` | Apache-2.0 | Mature but slower-moving; works fine for basic cases. |

**Recommendation: `com.networknt:json-schema-validator`.** Active, fast, integrates
cleanly with Jackson (`JsonNode` in/out without conversion).

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing deps ...
    implementation("com.networknt:json-schema-validator:1.5.6")
}
```

(Confirm latest version at <https://github.com/networknt/json-schema-validator>
when implementing; the example uses a representative version.)

### 8.2 Validation hook

A small validator service:

```java
package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FrontmatterValidator {

    private static final JsonSchemaFactory FACTORY =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** Cache compiled schemas keyed by (slug, version) — schemas are immutable
     *  once seeded, so caching is safe forever. Eviction only on app restart. */
    private final ConcurrentHashMap<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public void validate(Schema schema, JsonNode frontmatter) {
        var key = schema.slug() + "@" + schema.version();
        var compiledSchema = compiled.computeIfAbsent(key,
            k -> FACTORY.getSchema(schema.definition()));
        Set<ValidationMessage> errors = compiledSchema.validate(frontmatter);
        if (!errors.isEmpty()) {
            throw new InvalidFrontmatterException(schema.slug(), schema.version(), errors);
        }
    }
}
```

`InvalidFrontmatterException` is a new checked or runtime exception that carries
the validation messages; the editor screen surfaces them to the user in the
keystroke prompt.

Called from `DocumentRepository.save()`:

```java
public DocumentRow save(DocumentRow next, long editorId, String summary) {
    var schema = schemaRepo.find(next.typeSlug(), next.typeVersion())
        .orElseThrow(() -> new UnknownTypeException(next.typeSlug(), next.typeVersion()));
    validator.validate(schema, next.frontmatter());
    // ... rest of save flow ...
}
```

### 8.3 Performance

Schema documents are tiny (KB-scale) and immutable; the cache in
`FrontmatterValidator` keeps each compiled schema once per app instance.
Validation cost on a single document save is sub-millisecond. No concerns for
v1 throughput.

## 9. Consumer migration — files & bulletins

V10 leaves the legacy `files` and `bulletins` tables in place (as V6 does) and
expands the documents substrate. The next phase migrates the screens that still
read/write the legacy tables to instead read/write `documents` rows of the
appropriate `type_slug`. After that work, V11 can drop the legacy tables.

### 9.1 Bulletins → documents (type_slug='article')

**Read path:** Replace `BulletinRepository.listForDisplay()` and `findById()` with
queries against `documents` filtered by `type_slug='article'`. Pinning is in
`frontmatter->>'pinned'` (already populated by V6 backfill).

```sql
-- The query that replaces BulletinRepository.listForDisplay()
SELECT * FROM documents
 WHERE type_slug = 'article'
   AND deleted_at IS NULL
   AND status = 'published'
 ORDER BY (frontmatter->>'pinned')::boolean DESC NULLS LAST,
          updated_at DESC
 LIMIT 9;
```

**Write path:** `SysopBulletinNewScreen` saves through
`DocumentRepository.save()` with `typeSlug='article'`. Title and body go to the
expected columns; pinned goes into `frontmatter`.

**Affected files (rewrite or delete):**

- `app/src/main/java/io/aeyer/voidcore/bulletins/Bulletin.java` — delete; use
  `DocumentRow` directly
- `app/src/main/java/io/aeyer/voidcore/bulletins/BulletinRepository.java` — delete
- `app/src/main/java/io/aeyer/voidcore/ws/flow/view/BulletinView.java` — rewrite or
  delete; replace with `DocumentView` filtered queries
- `app/src/main/java/io/aeyer/voidcore/ws/flow/screen/impl/BulletinsListScreen.java`
  — rewrite to read via `DocumentView` filtered to `article`
- `app/src/main/java/io/aeyer/voidcore/ws/flow/screen/impl/BulletinViewScreen.java`
  — possibly redirect to `DocumentScreen` with `{slug}`; or rewrite to render
  articles via the same path
- `app/src/main/java/io/aeyer/voidcore/ws/flow/screen/impl/SysopBulletinsScreen.java`
  — rewrite for documents
- `app/src/main/java/io/aeyer/voidcore/ws/flow/screen/impl/SysopBulletinNewScreen.java`
  — rewrite for documents

### 9.2 Files → documents (type_slug='release') — voidcore vs SYSOP split

This is where the instance-overlay separation becomes visible. The current
`FilesListScreen` displays "files" but is actually showing the seven release
documents backfilled by V6. The right cleanup is:

- **In VoidCore core:** files area becomes a *generic* file area — no music
  metadata, just a generic file-handling screen with title, description,
  download. (Per the wishlist item 5; deferred to a later phase.)
- **In instance overlay:** the Releases screen (NEW) renders documents of
  `type_slug='release'` with the rich metadata view (artist, year, label, NFO
  body, etc.). Owns the SYSOP music catalogue.

For now, while SYSOP and voidcore are still the same repo:

**Read path:** Rewrite `FilesListScreen` and `FileViewScreen` to query
`documents` filtered by `type_slug='release'` via a new `ReleaseView` (or a
parameterised `DocumentView.byType('release')`). The display logic in
`FileViewScreen` already knows how to render NFO-style content; it just gets
fed `DocumentRow.body` instead of `FileRecord.nfoText`.

**Write path:** Sysop file CRUD screens (`SysopFilesScreen`,
`SysopFileNewScreen`, `SysopFileEditScreen`, `SysopFileDeleteConfirmScreen`)
rewrite to mutate `documents` of `type_slug='release'` via
`DocumentRepository.save()` / `delete()`.

**Affected files (rewrite or delete):**

- `app/src/main/java/io/aeyer/voidcore/files/FileRecord.java` — delete; use
  `DocumentRow` with the release frontmatter
- `app/src/main/java/io/aeyer/voidcore/files/FileRepository.java` — delete
- `app/src/main/java/io/aeyer/voidcore/ws/flow/view/FileView.java` — rewrite as
  `ReleaseView` or have callers use `DocumentView.byType('release')`
- `app/src/main/java/io/aeyer/voidcore/ws/flow/screen/impl/FilesListScreen.java`,
  `FileViewScreen.java`, `SysopFiles*Screen.java` — rewrite

### 9.3 V11 — drop legacy tables

After consumer migration is complete and verified:

```sql
-- V11__drop_legacy_files_bulletins.sql
DROP TABLE files;
DROP TABLE bulletins;
```

Run `./scripts/regenerate-jooq.sh` after this lands so the generated jOOQ classes
no longer carry references to the dropped tables.

V11 should not run until every test passes and a manual smoke test confirms the
files/bulletins UI flows still work via the documents path.

### 9.4 Slug stability and external links

V6's backfill produces slugs like `ctrlthry`, `bulletin-1`, etc. Existing external
links may reference these. Slug stability is a hard contract:

- Slugs never change for an existing document (no rename).
- Soft-deleted documents keep their slug; a fresh document cannot reuse a
  deleted slug until purged. (If this is too restrictive, add a `purged`
  marker and allow reuse after purge.)
- The `DocumentRepository.findByFilenameOrSlug` method handles legacy URL
  patterns like `?nfo=PATTERN9`.

## 10. Instance overlay deliverables

For the OSS-split context (per `the repo-split migration plan` plan,
§9): the instance overlay declares its scene-specific content as a Flyway
**repeatable migration** living outside VoidCore core's V-chain.

### 10.1 `R__instance_release_schema.sql` (overlay-side)

In the open-source split, VoidCore core ships V10 with five built-ins
(`note`, `article`, `howto`, `link`, `glossary`) — not `release`. The SYSOP
overlay re-introduces `release` via a repeatable migration:

```sql
-- instance overlay only.
-- Path in the overlay repo: instance/migrations/R__instance_release_schema.sql
-- Loaded via Flyway extra-locations:
--   spring.flyway.locations=classpath:db/migration,filesystem:/instance/migrations

INSERT INTO schemas (slug, version, label, description, definition, presentation, status)
VALUES ('release', 1, 'Release',
  'A published work — track, album, EP — with rich metadata.',
  '{...full JSON Schema as in §5 above...}'::jsonb,
  '{...presentation hints as in §5...}'::jsonb,
  'active')
ON CONFLICT (slug, version) DO NOTHING;
```

(The `ON CONFLICT DO NOTHING` makes the migration idempotent — Flyway repeatable
migrations run on every startup if their checksum has changed; using
`ON CONFLICT` keeps the seed safe against re-application.)

### 10.2 `R__instance_release_data.sql` (overlay-side)

Seeds the seven instance release documents. In VoidCore core these don't exist
(no V6 backfill of personal releases); they only show up in deployments running
the instance overlay.

```sql
-- Idempotent seed for SYSOP's release catalogue. Inserts only if absent.
INSERT INTO documents (slug, title, type_slug, type_version, body, frontmatter,
                       tags, author_id, visibility, status)
VALUES
  ('ctrlthry', 'CONTROL THEORY', 'release', 1,
    'Full NFO body...',
    '{"filename":"CTRLTHRY.ZIP",""artist":"sysop","year":2024,"label":"...","catalog_number":"...","genre":"industrial","external_url":"https://music.example.com/...","size_bytes":...,"download_count":0}'::jsonb,
    '{}'::text[],
    (SELECT id FROM users WHERE handle = 'sysop' LIMIT 1),
    'public', 'published')
ON CONFLICT (slug) DO NOTHING;

-- ... six more release rows ...
```

### 10.3 Releases screen (overlay-supplied)

The instance overlay supplies a `ReleasesScreen.java` that queries documents of
`type_slug='release'` with rich-metadata column rendering (artist, year, label,
catalog_number). In v1, this lives in the SYSOP codebase as plain Spring beans;
in v2 (per the OSS plan §9) it ships as a proper plugin loaded via the v2
manifest mechanism. For now: bean wiring, classpath ordering, and screen
registration discovery — rough but functional.

In VoidCore core, the corresponding screen is the generic `Files` screen
(per wishlist item 5), which handles real file uploads and downloads with no
music-specific knowledge.

### 10.4 The VoidCore core V10

When VoidCore core's first migration set is created from this repo (per the
OSS-split plan), V10 should be edited to:

- Remove the `release` schema seed (lines for `('release', 1, ...)` in step 2)
- Backfill expectations don't apply (no `files` / `bulletins` data exists in a
  fresh public repo)

Confirm this when the cutover happens. The `release` schema seed line in V10 is
the only instance-specific thing in the migration; everything else is generic.

## 11. Federation hooks (forward design only)

V10 doesn't implement federation, but its schema commits choices that make
future federation tractable. Documented here so they aren't accidentally
violated in adjacent work:

- **Slugs are stable identity.** A document's `slug` never changes after
  creation. This is what makes `voidcore://example.com/doc/danse-cybernetica`
  work as a long-term URI.
- **Schemas are addressable resources.** A schema's `(slug, version)` tuple is
  globally identifying within an instance. A peer that fetches a federated
  document with `type_slug='release', type_version=2` from `example.com` and
  doesn't know that schema can request it from
  `voidcore://example.com/types/release@2` and cache it.
- **Rev counter enables incremental sync.** A peer subscribed to "all releases
  from example.com" can poll `WHERE type_slug='release' AND rev > last_seen_rev`
  to fetch deltas. Without `rev`, every sync is a full fetch.
- **Soft delete is publishable.** A federated peer can be told "doc X was
  deleted at time T" via the `deleted_at` field, and remove it from caches
  without losing track that it existed. Hard purge is local-only.
- **Document revisions are local-only by default.** Federation publishes the
  current state of public documents; revision history stays with the
  authoritative instance unless explicit "fetch revision N" is implemented
  later.

The federation protocol design (peer handshake, rolling-code auth, message
types) is the subject of a separate v2.x design document; this migration
deliberately avoids prejudging it.

## 12. Phased delivery plan

Estimated effort assumes one focused engineer; halve for two, double for
part-time.

### Phase 1 — Land V10 schema (1–2 days)

1. Write `app/src/main/resources/db/migration/V10__document_substrate_typed.sql`
   per §4.
2. Write `DocumentsMigrationV10IntegrationTest` (Testcontainers postgres,
   applies V1–V10, asserts post-migration shape — see §13).
3. Run `./scripts/regenerate-jooq.sh` to regenerate jOOQ.
4. Verify generated classes in `app/src/jooq/java/io/aeyer/voidcore/jooq/tables/`
   include `Schemas`, updated `Documents` columns, updated
   `DocumentRevisions` columns.
5. Spin up local dev DB with Docker compose, run app, confirm Flyway applies
   V10 cleanly.

**Checkpoint:** V10 applied on a populated DB; jOOQ regenerated; existing read
paths still work (no app code changes yet — column rename `kind`→`type_slug` is
not yet reflected in Java; a brief jOOQ compilation error window during this
phase is acceptable and resolved in phase 2).

### Phase 2 — App-layer plumbing (3–5 days)

1. Add `com.networknt:json-schema-validator` dependency.
2. Create `Schema.java`, `SchemaStatus.java`, `SchemaRepository.java` (§6.1).
3. Create `FrontmatterValidator.java` (§8.2).
4. Update `DocumentRow.java` with new fields (§6.2). Cascade-update repository,
   views, screens that reference removed fields.
5. Create `BuiltinType` enum, retire `DocumentKind` references (§6.3). Update
   each affected file (DocumentScreen, DocsFacet*, etc.) to consult `typeSlug`
   instead of the enum directly.
6. Mark `FrontmatterSchema.java` `@Deprecated`; update consumers to use
   `SchemaRepository` for type metadata.
7. Update `DocumentRepository` write methods to use the save/delete/restore
   flow (§7).
8. Run `./gradlew test` — all existing tests should pass, plus new ones from
   phase 1.

**Checkpoint:** App builds and starts; all read flows work as before; new save
flow validates frontmatter against schemas at the application layer.

### Phase 3 — Consumer migration (3–5 days)

1. Migrate bulletins consumers to documents (§9.1).
2. Migrate files consumers to documents (§9.2 — internal-repo version, not the
   VoidCore core split).
3. Sysop CRUD screens rewritten for documents.
4. End-to-end manual smoke test: create release, edit it, view in
   FilesListScreen path (or new ReleasesScreen path), delete, restore.

**Checkpoint:** No application code reads or writes `files` or `bulletins`
tables. Both tables still exist (data preserved) but unused.

### Phase 4 — Drop legacy tables (V11) (½ day)

1. Write `V11__drop_legacy_files_bulletins.sql`.
2. Regenerate jOOQ (the `Files` and `Bulletins` jOOQ classes go away).
3. Run app, confirm clean startup, manual smoke test.

**Checkpoint:** Legacy tables gone. V6 backfill is now permanent (data lives
only in `documents`).

### Phase 5 — instance overlay split (separate plan; aligns with OSS-split work)

When the OSS split happens (per the existing plan):

1. SYSOP's `release` schema migrates from V10 (built-in) to overlay's
   `R__instance_release_schema.sql` (§10.1).
2. SYSOP's seven release documents migrate from V6 backfill output to overlay's
   `R__instance_release_data.sql` (§10.2).
3. VoidCore core's V10 has the `release` schema removed.
4. VoidCore core ships a generic Files screen; instance overlay supplies the
   Releases screen.

This phase is bookkeeping, not new design — the substrate is already in place.

## 13. Verification & test plan

### 13.1 SQL sanity queries (run after V10 applies)

```sql
-- 1. Schemas table populated with all 6 built-ins (or 5 in VoidCore core).
SELECT slug, version, status, label
  FROM schemas
 ORDER BY slug, version;
-- Expect: 6 rows (note, article, howto, link, glossary, release), all v1, all active.

-- 2. Every document references a valid schema row.
SELECT COUNT(*)
  FROM documents d
  LEFT JOIN schemas s
    ON s.slug = d.type_slug AND s.version = d.type_version
 WHERE s.id IS NULL;
-- Expect: 0.

-- 3. Documents all have rev = 1 (no prior revisions in V6's table).
SELECT MIN(rev), MAX(rev), COUNT(*)
  FROM documents;
-- Expect: min=1, max=1, count=10 (7 releases + 3 bulletins backfilled by V6).

-- 4. No documents are flagged as deleted.
SELECT COUNT(*) FROM documents WHERE deleted_at IS NOT NULL;
-- Expect: 0 immediately after migration.

-- 5. document_revisions rows (if any) all have rev set.
SELECT COUNT(*) FROM document_revisions WHERE rev IS NULL;
-- Expect: 0.

-- 6. Backfilled releases still validate against the release schema.
--    (Requires jq or app-side validation; not easily expressible in pure SQL.)

-- 7. Indexes exist.
SELECT indexname FROM pg_indexes WHERE tablename = 'documents'
 ORDER BY indexname;
-- Expect: documents_pkey, documents_search, documents_tags, documents_type_slug,
--         documents_author, documents_status, documents_updated, documents_live,
--         documents_deleted, documents_slug_key (UNIQUE).

-- 8. The documents_type_fk constraint is in place.
SELECT conname FROM pg_constraint
 WHERE conrelid = 'documents'::regclass AND contype = 'f';
-- Expect to include: documents_type_fk, documents_author_id_fkey, documents_deleted_by_fkey.
```

### 13.2 Integration test stub

Path: `app/src/test/java/io/aeyer/voidcore/documents/DocumentsMigrationV10IntegrationTest.java`

```java
@Testcontainers
class DocumentsMigrationV10IntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine")
        .withInitScript(null);

    @Test
    void v10_appliesCleanly_andSchemasAreSeeded() {
        // 1. Apply V1..V10 in sequence.
        flyway(pg).migrate();

        // 2. Assert schemas table has 6 active built-ins at v1.
        try (var c = pg.createConnection("")) {
            var ps = c.prepareStatement(
                "SELECT slug FROM schemas WHERE status = 'active' ORDER BY slug");
            var rs = ps.executeQuery();
            var slugs = new ArrayList<String>();
            while (rs.next()) slugs.add(rs.getString(1));
            assertThat(slugs).containsExactlyInAnyOrder(
                "article", "glossary", "howto", "link", "note", "release");
        }
    }

    @Test
    void v10_documentsKindRenamedToTypeSlug_andFkValid() {
        flyway(pg).migrate();
        try (var c = pg.createConnection("")) {
            var rs = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns " +
                " WHERE table_name = 'documents' AND column_name IN " +
                "       ('kind','type_slug','type_version','rev','deleted_at','deleted_by')")
                .executeQuery();
            var cols = new ArrayList<String>();
            while (rs.next()) cols.add(rs.getString(1));
            assertThat(cols).containsExactlyInAnyOrder(
                "type_slug", "type_version", "rev", "deleted_at", "deleted_by");
            assertThat(cols).doesNotContain("kind");
        }
    }

    @Test
    void v6BackfilledReleasesValidateAgainstReleaseSchema() {
        flyway(pg).migrate();
        // Load each backfilled release; validate frontmatter against
        // schemas[release@1].definition. All seven must validate.
        // (Use FrontmatterValidator from §8.2; this is a spring-boot test.)
    }

    @Test
    void v10_runOnFreshDbProducesZeroRevisions() {
        flyway(pg).migrate();
        try (var c = pg.createConnection("")) {
            var rs = c.prepareStatement(
                "SELECT COUNT(*) FROM document_revisions").executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
```

### 13.3 Manual smoke test checklist

After phases 2–3:

1. Create a new release via sysop UI. Confirm save.
2. Edit the release, change the artist field. Confirm save creates a revision.
3. Query `document_revisions` — confirm one row appears with `is_deletion=false`,
   the *previous* artist value, `rev` = previous documents.rev.
4. Confirm `documents.rev` incremented by 1.
5. Try to save with an invalid frontmatter (e.g., `year: "abc"`). Confirm
   validation refuses the save.
6. Soft-delete the release. Confirm it disappears from list views.
7. Inspect `document_revisions` — confirm a terminal revision with
   `is_deletion=true`.
8. Restore the release via sysop tooling. Confirm it returns to list views.
9. Check `documents.rev` is now original + 3 (initial + edit + delete + restore).

## 14. Open decisions

These are calls to make during implementation that this plan deliberately
defers — none affect the migration's shape, but each merits a quick decision:

- **Should `DocumentKind` be deleted entirely, or kept as `BuiltinType`?**
  Recommendation in §6.3: keep as `BuiltinType` for v1, reconsider in v1.x.
- **Permanent purge mechanics.** When does a sysop hard-delete a document? UI
  workflow, audit logging, FK cascade behaviour. Not in V10's scope; address
  in a future ADR.
- **Schema deprecation policy.** When does `status='active'` flip to
  `'deprecated'` for an old version? Suggested rule: when a new version is
  promoted, the prior one becomes deprecated automatically; admin tooling can
  override. Document as a project policy.
- **Slug reuse after purge.** If a doc is hard-purged (not soft-deleted), can a
  new doc claim the same slug? Probably yes; defer until purge is implemented.
- **Backfill of revisions for pre-V10 edits.** V6 ships with no revisions
  written; V10 includes a backfill that copies current document state to
  pre-existing revisions for fidelity (§4 step 4c). If the production database
  somehow accumulates revisions before V10 runs, the backfilled rows lose
  historical fidelity. Acceptable trade-off; documented in §4.
- **`anchor_document_id` interaction with type system.** The Anchor integration
  field is independent of typing for now; documents of any type can carry an
  anchor reference. No schema-level change needed.

## 15. Out of scope

- **Sysop UI for creating new types.** v2 work; for v1, types are added by
  editing an overlay's repeatable migration and restarting.
- **Schema migration tooling for breaking changes.** When `release@2` is
  introduced with required fields not present in `release@1` documents, no
  automatic migration runs — operators handle it via SQL or per-document
  re-edit. Worth a follow-up plan when this case actually arrives.
- **Schema inheritance / parent_slug.** Defer; no concrete need today.
- **Federation protocol implementation.** Hooks are noted in §11; protocol
  design is a separate document.
- **Plugin runtime (Rhino/GraalJS).** Per the platform direction, but v2 work,
  not v10.

## 16. File reference index

For the next implementer.

### SQL

- New: `app/src/main/resources/db/migration/V10__document_substrate_typed.sql`
- New (later): `app/src/main/resources/db/migration/V11__drop_legacy_files_bulletins.sql`
- Existing: `V1__initial_schema.sql`, `V6__documents_substrate.sql`,
  `V9__app_state.sql`

### Java — new

- `app/src/main/java/io/aeyer/voidcore/documents/Schema.java`
- `app/src/main/java/io/aeyer/voidcore/documents/SchemaStatus.java`
- `app/src/main/java/io/aeyer/voidcore/documents/SchemaRepository.java`
- `app/src/main/java/io/aeyer/voidcore/documents/FrontmatterValidator.java`
- `app/src/main/java/io/aeyer/voidcore/documents/InvalidFrontmatterException.java`
- `app/src/main/java/io/aeyer/voidcore/documents/UnknownTypeException.java`
- `app/src/main/java/io/aeyer/voidcore/documents/BuiltinType.java`

### Java — modified

- `app/src/main/java/io/aeyer/voidcore/documents/DocumentRow.java` — add fields
- `app/src/main/java/io/aeyer/voidcore/documents/DocumentRepository.java` — add
  save/delete/restore; introduce revision-write flow
- `app/src/main/java/io/aeyer/voidcore/documents/FrontmatterSchema.java` —
  `@Deprecated`, fall back to `SchemaRepository`
- All `DocsFacet*Screen.java`, `DocumentScreen.java`,
  `DocsResultsScreen.java` — adapt to `typeSlug` instead of `DocumentKind`
- `BulletinsListScreen.java`, `BulletinViewScreen.java`,
  `SysopBulletinsScreen.java`, `SysopBulletinNewScreen.java` — migrate to
  documents (phase 3)
- `FilesListScreen.java`, `FileViewScreen.java`, `SysopFiles*Screen.java` —
  migrate to documents (phase 3)

### Java — delete (after phase 3)

- `app/src/main/java/io/aeyer/voidcore/bulletins/Bulletin.java`
- `app/src/main/java/io/aeyer/voidcore/bulletins/BulletinRepository.java`
- `app/src/main/java/io/aeyer/voidcore/files/FileRecord.java`
- `app/src/main/java/io/aeyer/voidcore/files/FileRepository.java`

### Java — delete (after V11)

- jOOQ-generated `Files` and `Bulletins` table classes (auto-removed by
  regenerate-jooq.sh).

### Tests

- New: `app/src/test/java/io/aeyer/voidcore/documents/DocumentsMigrationV10IntegrationTest.java`
- New: tests for `SchemaRepository`, `FrontmatterValidator`,
  `DocumentRepository` save/delete/restore
- Update: existing `DocumentsMigrationIntegrationTest` (V6) — confirm
  post-V10 the original V6 backfill assertions still hold

### Build config

- Modified: `app/build.gradle.kts` — add
  `com.networknt:json-schema-validator` dependency
- Unchanged: `scripts/regenerate-jooq.sh` (still applies V1..Vmax in sequence)
- Unchanged: `app/src/main/resources/application.yml` (this migration alone
  does not require Flyway extra-locations; the instance overlay phase does)

### Documentation

- Modified: `SPEC-documents.md` — note that §2.1's `kind` enum is superseded by
  the typed-schemas model; update sections that reference `kind` to reference
  `type_slug + type_version`. Keep the spec; mark the parts V10 supersedes.
- New: this document, `MIGRATION-document-substrate.md`
- Future: a new ADR (e.g., ADR-033) recording the decision to promote types to
  data, with cross-references to ADR-023 (information primacy).

---

**End of plan.** Implementer should be able to start at §4, write V10 first,
verify with §13.1, then walk §6–§9 in order. Questions: cross-reference the
file paths in §16, the SQL in §4, and the existing V6 source for any nuance
this document elides.
