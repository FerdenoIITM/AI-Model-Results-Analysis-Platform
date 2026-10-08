-- Test-only accounts for the isolated test stack (never loaded into a real database).
-- Every account's password is the test password "Test-Pass-123!" (bcrypt, cost 10).
INSERT INTO users (id, email, password_hash, role, display_name, created_by, is_active) VALUES
  ('00000000-0000-4000-8000-000000000001', 'super@amrap.test',
   '$2y$10$LqxugH6OM.rxd7VDWaBASuqWXh7JKWZVVJXbzeHHvfXeYsE7wQVmi', 'superadmin', 'Test Superadmin', NULL, TRUE),
  ('00000000-0000-4000-8000-000000000002', 'admin@amrap.test',
   '$2y$10$LqxugH6OM.rxd7VDWaBASuqWXh7JKWZVVJXbzeHHvfXeYsE7wQVmi', 'admin', 'Test Admin',
   '00000000-0000-4000-8000-000000000001', TRUE),
  ('00000000-0000-4000-8000-000000000003', 'member@amrap.test',
   '$2y$10$LqxugH6OM.rxd7VDWaBASuqWXh7JKWZVVJXbzeHHvfXeYsE7wQVmi', 'member', 'Test Member',
   '00000000-0000-4000-8000-000000000002', TRUE),
  ('00000000-0000-4000-8000-000000000004', 'inactive@amrap.test',
   '$2y$10$LqxugH6OM.rxd7VDWaBASuqWXh7JKWZVVJXbzeHHvfXeYsE7wQVmi', 'member', 'Inactive Member',
   '00000000-0000-4000-8000-000000000002', FALSE);
