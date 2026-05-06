-- =============================================================================
-- V13 — Multi-room chat foundation
--
-- Introduces room metadata, room membership, and room-scoped messages while
-- preserving the legacy chat_messages table for historical compatibility.
-- Existing single-room traffic is backfilled into the default `general` room.
-- =============================================================================

CREATE TABLE chat_rooms (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug        TEXT NOT NULL UNIQUE,
  label       TEXT NOT NULL,
  is_private  BOOLEAN NOT NULL DEFAULT false,
  is_active   BOOLEAN NOT NULL DEFAULT true,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_room_members (
  room_id     BIGINT NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE,
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (room_id, user_id)
);

CREATE TABLE chat_room_messages (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  room_id     BIGINT NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE,
  author_id   BIGINT NOT NULL REFERENCES users(id),
  body        TEXT NOT NULL,
  kind        TEXT NOT NULL DEFAULT 'msg',
  posted_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_rooms_sort
  ON chat_rooms (sort_order ASC, slug ASC);

CREATE INDEX idx_chat_room_messages_room_posted
  ON chat_room_messages (room_id, posted_at DESC);

INSERT INTO chat_rooms (slug, label, sort_order)
VALUES ('general', 'General', 1)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO chat_room_messages (room_id, author_id, body, kind, posted_at)
SELECT r.id, m.author_id, m.body, m.kind, m.posted_at
  FROM chat_messages m
  JOIN chat_rooms r ON r.slug = 'general'
 WHERE NOT EXISTS (
   SELECT 1
     FROM chat_room_messages crm
    WHERE crm.room_id = r.id
      AND crm.author_id = m.author_id
      AND crm.kind = m.kind
      AND crm.body = m.body
      AND crm.posted_at = m.posted_at
 );
