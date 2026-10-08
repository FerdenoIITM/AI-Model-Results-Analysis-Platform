-- Schema produced by infra/db/migrations (V1, V3, V4).

SELECT t.ok(EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgcrypto'), 'pgcrypto extension installed');
SELECT t.ok(EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector'), 'vector extension installed');

SELECT t.eq(
    (SELECT array_agg(enumlabel::text ORDER BY enumsortorder) FROM pg_enum WHERE enumtypid = 'user_role'::regtype),
    ARRAY['superadmin', 'admin', 'member'],
    'user_role enum has exactly superadmin, admin, member');

SELECT t.eq(
    (SELECT array_agg(table_name::text ORDER BY table_name) FROM information_schema.tables
      WHERE table_schema = 'public' AND table_type = 'BASE TABLE'),
    ARRAY['result_chunks', 'results', 'users'],
    'public schema has exactly users, results, result_chunks');

-- Column type and nullability: table.column -> "data_type|is_nullable"
WITH expected(tbl, col, spec) AS (VALUES
    ('users', 'id', 'uuid|NO'),
    ('users', 'email', 'character varying(320)|NO'),
    ('users', 'password_hash', 'character varying(255)|NO'),
    ('users', 'role', 'USER-DEFINED|NO'),
    ('users', 'display_name', 'character varying(120)|NO'),
    ('users', 'created_by', 'uuid|YES'),
    ('users', 'is_active', 'boolean|NO'),
    ('users', 'last_login_at', 'timestamp with time zone|YES'),
    ('users', 'password_changed_at', 'timestamp with time zone|YES'),
    ('users', 'created_at', 'timestamp with time zone|NO'),
    ('users', 'updated_at', 'timestamp with time zone|NO'),
    ('results', 'id', 'uuid|NO'),
    ('results', 'model_name', 'character varying(255)|NO'),
    ('results', 'version', 'character varying(100)|NO'),
    ('results', 'iteration_id', 'character varying(255)|NO'),
    ('results', 'json_path', 'text|NO'),
    ('results', 'size_bytes', 'bigint|NO'),
    ('results', 'content_hash', 'character varying(128)|NO'),
    ('results', 'inserted_at', 'timestamp with time zone|NO'),
    ('results', 'is_vectorized', 'boolean|NO'),
    ('results', 'vectorized_at', 'timestamp with time zone|YES'),
    ('results', 'created_by', 'uuid|YES'),
    ('results', 'category', 'character varying(120)|YES'),
    ('results', 'sub_category', 'character varying(120)|YES'),
    ('results', 'confidence_accuracy', 'double precision|YES'),
    ('results', 'sensitivity', 'double precision|YES'),
    ('results', 'specificity', 'double precision|YES'),
    ('results', 'f1_score', 'double precision|YES'),
    ('results', 'recall', 'double precision|YES'),
    ('results', 'precision', 'double precision|YES'),
    ('result_chunks', 'id', 'uuid|NO'),
    ('result_chunks', 'content', 'text|YES'),
    ('result_chunks', 'metadata', 'jsonb|YES'),
    ('result_chunks', 'embedding', 'USER-DEFINED|YES')
), actual AS (
    SELECT table_name::text AS tbl, column_name::text AS col,
           CASE WHEN character_maximum_length IS NOT NULL
                THEN data_type || '(' || character_maximum_length || ')'
                ELSE data_type END || '|' || is_nullable AS spec
      FROM information_schema.columns WHERE table_schema = 'public'
)
SELECT t.eq(a.spec, e.spec, 'column ' || e.tbl || '.' || e.col || ' is ' || e.spec)
  FROM expected e LEFT JOIN actual a USING (tbl, col);

SELECT t.eq(
    (SELECT count(*)::int FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'results'),
    19, 'results has no unexpected columns');

SELECT t.eq(format_type(atttypid, atttypmod), 'vector(768)', 'embedding is vector(768) for nomic-embed-text')
  FROM pg_attribute WHERE attrelid = 'result_chunks'::regclass AND attname = 'embedding';

SELECT t.ok(column_default LIKE 'gen_random_uuid()%', table_name || '.id defaults to gen_random_uuid()')
  FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'id' AND table_name IN ('users', 'results');
SELECT t.ok(column_default IS NULL, 'result_chunks.id has no default (Spring AI supplies ids)')
  FROM information_schema.columns WHERE table_name = 'result_chunks' AND column_name = 'id';
SELECT t.eq(column_default, 'false', 'results.is_vectorized defaults to false')
  FROM information_schema.columns WHERE table_name = 'results' AND column_name = 'is_vectorized';
SELECT t.eq(column_default, 'true', 'users.is_active defaults to true')
  FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'is_active';

WITH expected(name) AS (VALUES
    ('uq_users_email'), ('ck_users_email_format'), ('fk_users_created_by'), ('fk_results_created_by'),
    ('ck_results_category_trimmed'), ('ck_results_sub_category_trimmed'), ('ck_results_confidence_accuracy'),
    ('ck_results_sensitivity'), ('ck_results_specificity'), ('ck_results_f1_score'), ('ck_results_recall'),
    ('ck_results_precision'))
SELECT t.ok(EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conname = e.name), 'constraint ' || e.name || ' exists')
  FROM expected e;

SELECT t.eq(
    (SELECT confdeltype::text FROM pg_constraint WHERE conname = 'fk_users_created_by'), 'r',
    'users.created_by FK is ON DELETE RESTRICT');
SELECT t.eq(
    (SELECT confdeltype::text FROM pg_constraint WHERE conname = 'fk_results_created_by'), 'n',
    'results.created_by FK is ON DELETE SET NULL');
SELECT t.eq(
    (SELECT confupdtype::text FROM pg_constraint WHERE conname = 'fk_results_created_by'), 'c',
    'results.created_by FK is ON UPDATE CASCADE');

WITH expected(name) AS (VALUES
    ('idx_users_role'), ('idx_users_created_by'), ('idx_users_is_active'), ('idx_results_model_version'),
    ('idx_results_inserted_at'), ('idx_results_is_vectorized'), ('idx_result_chunks_embedding'),
    ('idx_result_chunks_result_id'), ('idx_results_category_sub_category'), ('idx_results_sub_category'),
    ('idx_results_confidence_accuracy'), ('idx_results_sensitivity'), ('idx_results_specificity'),
    ('idx_results_f1_score'), ('idx_results_recall'), ('idx_results_precision'))
SELECT t.ok(EXISTS (SELECT 1 FROM pg_indexes i WHERE i.indexname = e.name), 'index ' || e.name || ' exists')
  FROM expected e;

SELECT t.ok(indexdef LIKE '%USING hnsw%vector_cosine_ops%', 'embedding index is HNSW with cosine ops')
  FROM pg_indexes WHERE indexname = 'idx_result_chunks_embedding';
SELECT t.ok(indexdef LIKE '%WHERE (is_vectorized = false)%', 'is_vectorized index is partial on pending rows')
  FROM pg_indexes WHERE indexname = 'idx_results_is_vectorized';

SELECT t.ok(EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_users_set_updated_at' AND NOT tgisinternal),
    'users updated_at trigger exists');
