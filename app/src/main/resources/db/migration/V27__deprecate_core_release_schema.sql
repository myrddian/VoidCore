-- =============================================================================
-- V27 — Reclassify `release` as a compatibility schema.
--
-- Core no longer treats `release` as one of its native built-in document
-- types; future instance/overlay repos should define and own that schema
-- contract themselves. We keep the historical row as a compatibility bridge so
-- migrated installs and the still-temporary core release screens continue to
-- resolve a known schema/version during the extraction window.
-- =============================================================================

UPDATE schemas
   SET label = 'Release (compatibility)',
       description = 'Compatibility bridge for legacy file/release flows while release extraction moves to instance overlays.',
       presentation = presentation
         || '{"overlay_owned":true,"core_compatibility_only":true}'::jsonb,
       status = 'deprecated',
       updated_at = now()
 WHERE slug = 'release'
   AND version = 1;
