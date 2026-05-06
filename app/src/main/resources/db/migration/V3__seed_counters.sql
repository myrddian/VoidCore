-- =============================================================================
-- V3 — Site-wide counters seed.
--
-- The caller_count starts at 1337 per SPEC §3 ("Seed data") so the first real
-- caller is #1338. The bulletins viewer reads this value to render the
-- {{call_no}} placeholder in the welcome bulletin.
-- =============================================================================

INSERT INTO counters (key, value) VALUES ('caller_count', 1337);
