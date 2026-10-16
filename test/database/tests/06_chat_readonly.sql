-- Chat read-only view + role from V5 / V5b.

SELECT t.ok(
    EXISTS (
        SELECT 1 FROM information_schema.views
        WHERE table_schema = 'public' AND table_name = 'v_chat_results'
    ),
    'v_chat_results view exists');

SELECT t.ok(
    EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'amrap_chat_ro'),
    'amrap_chat_ro role exists');

SELECT t.ok(
    NOT has_table_privilege('amrap_chat_ro', 'users', 'SELECT'),
    'amrap_chat_ro cannot SELECT users');

SELECT t.ok(
    has_table_privilege('amrap_chat_ro', 'v_chat_results', 'SELECT'),
    'amrap_chat_ro can SELECT v_chat_results');

SELECT t.ok(
    NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'v_chat_results'
          AND column_name IN ('json_path', 'content_hash', 'created_by', 'password_hash')
    ),
    'v_chat_results excludes sensitive columns');
