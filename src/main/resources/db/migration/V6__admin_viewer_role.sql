-- Read-only admin role for the public demo account.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('ROLE_ADMIN', 'ROLE_ADMIN_VIEWER', 'ROLE_USER'));
