-- results: required fields, data types, metric and label constraints, nulls, duplicates, FKs.

TRUNCATE users, results, result_chunks CASCADE;

SELECT t.lives($$SELECT t.new_result('CardioNet', 97.5)$$, 'valid result inserts');
SELECT t.ok((SELECT id IS NOT NULL AND inserted_at IS NOT NULL AND is_vectorized = false AND vectorized_at IS NULL
               FROM results WHERE model_name = 'CardioNet'), 'id, inserted_at and is_vectorized defaults apply');

-- Required fields
SELECT t.throws(format($$INSERT INTO results (%s) VALUES (%s)$$,
        array_to_string(ARRAY['version','iteration_id','json_path','size_bytes','content_hash'], ','),
        $$'1','i','x.json',1,'h'$$), '23502', 'model_name is required');
SELECT t.throws($$INSERT INTO results (model_name, iteration_id, json_path, size_bytes, content_hash) VALUES ('M','i','x',1,'h')$$,
    '23502', 'version is required');
SELECT t.throws($$INSERT INTO results (model_name, version, json_path, size_bytes, content_hash) VALUES ('M','1','x',1,'h')$$,
    '23502', 'iteration_id is required');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, size_bytes, content_hash) VALUES ('M','1','i',1,'h')$$,
    '23502', 'json_path is required');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, content_hash) VALUES ('M','1','i','x',1)$$,
    '23502', 'size_bytes is required');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes) VALUES ('M','1','i','x',1)$$,
    '23502', 'content_hash is required');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, is_vectorized)
                  VALUES ('M','1','i','x',1,'h',NULL)$$, '23502', 'is_vectorized cannot be NULL');

-- Lengths and types
SELECT t.throws($$SELECT t.new_result(repeat('m', 256))$$, '22001', 'model_name longer than 255 chars is rejected');
SELECT t.lives($$SELECT t.new_result(repeat('m', 255))$$, 'model_name of exactly 255 chars is accepted');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash)
                  VALUES ('M', repeat('v', 101), 'i', 'x', 1, 'h')$$, '22001', 'version longer than 100 chars is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash)
                  VALUES ('M', '1', 'i', 'x', 'big', 'h')$$, '22P02', 'non-numeric size_bytes is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, confidence_accuracy)
                  VALUES ('M', '1', 'i', 'x', 1, 'h', 'high')$$, '22P02', 'non-numeric metric is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, inserted_at)
                  VALUES ('M', '1', 'i', 'x', 1, 'h', 'yesterday-ish')$$, '22007', 'malformed timestamp is rejected');
SELECT t.lives($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash)
                 VALUES ('Big', '1', 'i', 'x', 9223372036854775807, 'h')$$, 'size_bytes holds the bigint maximum');

-- Metric range constraints, every metric
DO $$
DECLARE
    col text;
BEGIN
    FOREACH col IN ARRAY ARRAY['confidence_accuracy','sensitivity','specificity','f1_score','recall','precision'] LOOP
        PERFORM t.lives(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                               VALUES ('B','1','i','x',1,'h',0)$f$, col), col || ' accepts 0');
        PERFORM t.lives(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                               VALUES ('B','1','i','x',1,'h',100)$f$, col), col || ' accepts 100');
        PERFORM t.lives(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                               VALUES ('B','1','i','x',1,'h',NULL)$f$, col), col || ' accepts NULL');
        PERFORM t.throws(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                                VALUES ('B','1','i','x',1,'h',-0.0001)$f$, col), '23514', col || ' rejects -0.0001');
        PERFORM t.throws(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                                VALUES ('B','1','i','x',1,'h',100.0001)$f$, col), '23514', col || ' rejects 100.0001');
        PERFORM t.throws(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                                VALUES ('B','1','i','x',1,'h','NaN')$f$, col), '23514', col || ' rejects NaN');
        PERFORM t.throws(format($f$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, %I)
                                VALUES ('B','1','i','x',1,'h','Infinity')$f$, col), '23514', col || ' rejects Infinity');
    END LOOP;
END
$$;

-- Category / sub category must be trimmed and non-empty
SELECT t.lives($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, category, sub_category)
                 VALUES ('L','1','i','x',1,'h','Cardiology','Aortic Valve')$$, 'trimmed labels are accepted');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, category)
                  VALUES ('L','1','i','x',1,'h','')$$, '23514', 'empty category is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, category)
                  VALUES ('L','1','i','x',1,'h','  ')$$, '23514', 'blank category is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, category)
                  VALUES ('L','1','i','x',1,'h',' Cardiology')$$, '23514', 'untrimmed category is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, sub_category)
                  VALUES ('L','1','i','x',1,'h','Valve ')$$, '23514', 'untrimmed sub_category is rejected');
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, category)
                  VALUES ('L','1','i','x',1,'h',repeat('c', 121))$$, '22001', 'category longer than 120 chars is rejected');

-- Duplicate data is allowed: the same payload can be submitted more than once.
SELECT t.lives($$SELECT t.new_result('Dup', 50)$$, 'first duplicate inserts');
SELECT t.lives($$SELECT t.new_result('Dup', 50)$$, 'second identical row inserts');
SELECT t.eq((SELECT count(DISTINCT id)::int FROM results WHERE model_name = 'Dup'), 2, 'duplicates get distinct ids');

-- Creator FK
SELECT t.throws($$INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, created_by)
                  VALUES ('F','1','i','x',1,'h',gen_random_uuid())$$, '23503', 'created_by must reference an existing user');
DO $$
DECLARE
    owner uuid := t.new_user('owner@x.io', 'superadmin');
    rid uuid;
BEGIN
    INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, created_by)
    VALUES ('Owned','1','i','x',1,'h',owner) RETURNING id INTO rid;
    DELETE FROM users WHERE id = owner;
    PERFORM t.ok((SELECT created_by IS NULL FROM results WHERE id = rid), 'deleting the creator keeps the result with created_by NULL');
END
$$;

-- Vectorization flag round trip used by the worker and reindex
DO $$
DECLARE
    rid uuid := t.new_result('Vec');
BEGIN
    UPDATE results SET is_vectorized = true, vectorized_at = now() WHERE id = rid;
    PERFORM t.ok((SELECT is_vectorized AND vectorized_at IS NOT NULL FROM results WHERE id = rid), 'row can be marked vectorized');
    UPDATE results SET is_vectorized = false, vectorized_at = NULL;
    PERFORM t.eq((SELECT count(*)::int FROM results WHERE is_vectorized), 0, 'reindex reset clears every flag');
END
$$;
