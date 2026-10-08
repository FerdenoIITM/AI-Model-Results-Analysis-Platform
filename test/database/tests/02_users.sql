-- users: required fields, data types, constraints, duplicates, FKs, trigger.

TRUNCATE users, results, result_chunks CASCADE;

SELECT t.lives($$SELECT t.new_user('valid.user+tag@example.co')$$, 'valid user inserts');

SELECT t.eq((SELECT is_active FROM users WHERE email = 'valid.user+tag@example.co'), true, 'is_active defaults to true');
SELECT t.ok((SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM users WHERE email = 'valid.user+tag@example.co'),
    'created_at and updated_at are populated');

-- Required fields
SELECT t.throws($$INSERT INTO users (password_hash, role, display_name) VALUES ('h', 'member', 'N')$$, '23502', 'email is required');
SELECT t.throws($$INSERT INTO users (email, role, display_name) VALUES ('a@x.io', 'member', 'N')$$, '23502', 'password_hash is required');
SELECT t.throws($$INSERT INTO users (email, password_hash, display_name) VALUES ('a@x.io', 'h', 'N')$$, '23502', 'role is required');
SELECT t.throws($$INSERT INTO users (email, password_hash, role) VALUES ('a@x.io', 'h', 'member')$$, '23502', 'display_name is required');
SELECT t.throws($$INSERT INTO users (email, password_hash, role, display_name, is_active) VALUES ('a@x.io', 'h', 'member', 'N', NULL)$$,
    '23502', 'is_active cannot be NULL');

-- Duplicates and email format
SELECT t.throws($$SELECT t.new_user('valid.user+tag@example.co')$$, '23505', 'duplicate email is rejected');
SELECT t.throws($$SELECT t.new_user('Upper@Example.com')$$, '23514', 'uppercase email is rejected (must be stored lowercase)');
SELECT t.throws($$SELECT t.new_user('no-at-sign.example.com')$$, '23514', 'email without @ is rejected');
SELECT t.throws($$SELECT t.new_user('a@b')$$, '23514', 'email without TLD is rejected');
SELECT t.throws($$SELECT t.new_user('')$$, '23514', 'empty email is rejected');
SELECT t.throws($$SELECT t.new_user('a b@example.com')$$, '23514', 'email with space is rejected');

-- Data types and lengths
SELECT t.throws($$SELECT t.new_user(repeat('a', 310) || '@example.com')$$, '22001', 'email longer than 320 chars is rejected');
SELECT t.throws($$INSERT INTO users (email, password_hash, role, display_name) VALUES ('b@x.io', 'h', 'member', repeat('n', 121))$$,
    '22001', 'display_name longer than 120 chars is rejected');
SELECT t.lives($$INSERT INTO users (email, password_hash, role, display_name) VALUES ('c@x.io', 'h', 'member', repeat('n', 120))$$,
    'display_name of exactly 120 chars is accepted');
SELECT t.throws($$INSERT INTO users (email, password_hash, role, display_name) VALUES ('d@x.io', 'h', 'owner', 'N')$$,
    '22P02', 'unknown role value is rejected');
SELECT t.throws($$INSERT INTO users (email, password_hash, role, display_name) VALUES ('e@x.io', 'h', 'ADMIN', 'N')$$,
    '22P02', 'role enum is case-sensitive');
SELECT t.throws($$INSERT INTO users (id, email, password_hash, role, display_name) VALUES ('not-a-uuid', 'f@x.io', 'h', 'member', 'N')$$,
    '22P02', 'malformed uuid is rejected');
SELECT t.throws($$INSERT INTO users (email, password_hash, role, display_name, is_active) VALUES ('g@x.io', 'h', 'member', 'N', 'maybe')$$,
    '22P02', 'non-boolean is_active is rejected');

-- Ownership FK
SELECT t.throws($$SELECT t.new_user('orphan@x.io', 'member', gen_random_uuid())$$, '23503', 'created_by must reference an existing user');

DO $$
DECLARE
    boss uuid := t.new_user('boss@x.io', 'admin');
    worker uuid := t.new_user('worker@x.io', 'member', boss);
BEGIN
    PERFORM t.throws(format('DELETE FROM users WHERE id = %L', boss), '23503',
        'deleting a creator with dependent users is restricted');
    UPDATE users SET created_by = NULL WHERE id = worker;
    PERFORM t.lives(format('DELETE FROM users WHERE id = %L', boss), 'creator can be deleted once dependents are reassigned');
END
$$;

DO $$
DECLARE
    old_id uuid := t.new_user('cascade.boss@x.io', 'admin');
    new_id uuid := gen_random_uuid();
    worker uuid := t.new_user('cascade.worker@x.io', 'member', old_id);
BEGIN
    UPDATE users SET id = new_id WHERE id = old_id;
    PERFORM t.eq((SELECT created_by FROM users WHERE id = worker), new_id, 'created_by follows creator id changes (ON UPDATE CASCADE)');
END
$$;

-- updated_at trigger: NOW() is transaction time, so compare across separate statements.
UPDATE users SET updated_at = '2000-01-01T00:00:00Z' WHERE email = 'c@x.io';
SELECT t.eq((SELECT updated_at FROM users WHERE email = 'c@x.io') > '2000-01-02T00:00:00Z'::timestamptz, true,
    'trigger overrides manual updated_at with now() on UPDATE');
UPDATE users SET display_name = 'Renamed' WHERE email = 'c@x.io';
SELECT t.ok((SELECT updated_at >= created_at FROM users WHERE email = 'c@x.io'), 'updated_at is never before created_at');

-- Null handling
SELECT t.ok((SELECT last_login_at IS NULL AND password_changed_at IS NULL FROM users WHERE email = 'valid.user+tag@example.co'),
    'optional timestamps stay NULL until set');

-- Indexed lookups by role and creator return correct rows
SELECT t.eq((SELECT count(*)::int FROM users WHERE role = 'admin'), 1, 'filter by role');
