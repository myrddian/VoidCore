CREATE TABLE instance_features (
  feature_slug TEXT PRIMARY KEY,
  enabled      BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO instance_features(feature_slug, enabled) VALUES
  ('announcements', TRUE),
  ('releases', TRUE),
  ('info_docs', TRUE),
  ('message_board', TRUE),
  ('oneliners', TRUE),
  ('chat', TRUE),
  ('voidmail', TRUE),
  ('polls', TRUE),
  ('doors', TRUE);
