-- Restore the evolved #93 fortune copy without mutating the historical
-- V7 seed migration. Existing databases already applied V7 with the
-- original text; this follow-up migration carries the content change
-- forward in a Flyway-safe way.
UPDATE fortunes
SET text = 'structure outlasts spectacle.'
WHERE text = 'industrial frequencies, civilian receivers.';
