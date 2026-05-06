-- Fresh databases hit Flyway before SysopBootstrap (Spring's
-- ApplicationRunner), so V6's document backfill needs a temporary sysop row
-- in place before it runs. Insert one as soon as the users table exists and
-- no real sysop has been created yet; SysopBootstrap later rewrites the
-- sentinel into the configured real sysop on first boot.
DO $$
BEGIN
  IF EXISTS (
      SELECT 1
      FROM information_schema.tables
      WHERE table_schema = 'public'
        AND table_name = 'users'
  ) AND NOT EXISTS (
      SELECT 1
      FROM users
      WHERE is_sysop = true
  ) THEN
    INSERT INTO users (handle, pw_hash, is_sysop)
    VALUES ('BOOTSTRAP-SYSOP', 'BOOTSTRAP_SENTINEL', true);
  END IF;
END $$;
