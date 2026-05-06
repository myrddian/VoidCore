-- =============================================================================
-- V12 — Preserve legacy ids in document frontmatter, then drop legacy tables.
--
-- By this point all bulletin/file consumers read and write through the document
-- substrate. The old tables remain only as compatibility lookup sources for
-- stale screen state / deep links that still carry historic ids.
--
-- Before dropping them, preserve those ids on the document rows themselves:
--   release docs  -> frontmatter.legacy_file_id
--   article docs  -> frontmatter.legacy_bulletin_id
-- =============================================================================

-- Preserve the old file id on backfilled release documents, matched by filename.
UPDATE documents d
   SET frontmatter = jsonb_set(
         d.frontmatter,
         '{legacy_file_id}',
         to_jsonb(f.id),
         true
       )
  FROM files f
 WHERE d.type_slug = 'release'
   AND d.deleted_at IS NULL
   AND lower(d.frontmatter->>'filename') = lower(f.filename)
   AND NOT (d.frontmatter ? 'legacy_file_id');

-- Preserve the old bulletin id on backfilled article documents, matched by the
-- V6 slug convention bulletin-<id>.
UPDATE documents d
   SET frontmatter = jsonb_set(
         d.frontmatter,
         '{legacy_bulletin_id}',
         to_jsonb(b.id),
         true
       )
  FROM bulletins b
 WHERE d.type_slug = 'article'
   AND d.deleted_at IS NULL
   AND d.slug = 'bulletin-' || b.id
   AND NOT (d.frontmatter ? 'legacy_bulletin_id');

DROP TABLE files;
DROP TABLE bulletins;
