-- =============================================================================
-- V22 — Direct-message chat rooms
--
-- Adds an explicit DM room flag so private one-to-one chat threads can reuse
-- the existing chat room + ACL system without cluttering the public room list.
-- =============================================================================

ALTER TABLE chat_rooms
  ADD COLUMN IF NOT EXISTS is_dm BOOLEAN NOT NULL DEFAULT false;
