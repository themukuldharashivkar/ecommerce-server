-- Benchmark helper: removes everything V2__indexes.sql adds, to measure the "before" state.
DO $$ DECLARE r record; BEGIN
  FOR r IN SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE 'idx_%' LOOP
    EXECUTE 'DROP INDEX ' || r.indexname;
  END LOOP;
END $$;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uk_users_email;
ALTER TABLE cart DROP CONSTRAINT IF EXISTS uk_cart_user;
VACUUM ANALYZE;
