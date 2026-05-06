-- v1.6 TUI framework: per-session, per-app scratchpad for the
-- ScreenApp framework. Used by the modal editor's 15-second
-- snapshot for reconnect-survival; framework-level so any future
-- ScreenApp gets snapshot/restore for free.
ALTER TABLE sessions
  ADD COLUMN app_state JSONB NOT NULL DEFAULT '{}'::jsonb;

-- GIN index isn't worth it at v1 traffic — most reads are by
-- (session_id, app_key) and the JSONB is small. Add when profiling
-- says otherwise.
