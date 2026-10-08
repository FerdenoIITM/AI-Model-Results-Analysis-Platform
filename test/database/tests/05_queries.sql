-- Filtering, sorting, pagination and index usage on a 10,000-row dataset.
-- Queries mirror what the API's JPA specifications generate.

TRUNCATE users, results, result_chunks CASCADE;

INSERT INTO results (model_name, version, iteration_id, json_path, size_bytes, content_hash, inserted_at,
                     is_vectorized, category, sub_category, confidence_accuracy, sensitivity, recall)
SELECT (ARRAY['CardioNet','ChestScan','NeuroSlice','DermaVision'])[1 + i % 4],
       '1.' || (i % 3),
       to_char(timestamp '2026-01-01' + i * interval '1 minute', 'YYYYMMDDHH24MISS'),
       i || '.json', 100 + i, md5(i::text),
       timestamptz '2026-01-01T00:00:00Z' + i * interval '1 minute',
       i % 2 = 0,
       CASE WHEN i % 10 = 0 THEN NULL ELSE (ARRAY['Cardiology','Radiology','Neurology'])[1 + i % 3] END,
       CASE WHEN i % 10 = 0 THEN NULL ELSE (ARRAY['Valve','Lung','Tumor','Rhythm'])[1 + i % 4] END,
       CASE WHEN i % 20 = 0 THEN NULL ELSE (i % 1001) / 10.0 END,
       (i * 7 % 1001) / 10.0,
       (i * 13 % 1001) / 10.0
  FROM generate_series(1, 10000) AS i;
ANALYZE results;

SELECT t.eq((SELECT count(*)::int FROM results), 10000, '10,000 rows seeded');

-- Filtering
SELECT t.eq((SELECT count(*)::int FROM results WHERE model_name IN ('CardioNet')), 2500, 'filter by one model');
SELECT t.eq((SELECT count(*)::int FROM results WHERE model_name IN ('CardioNet', 'ChestScan')), 5000, 'filter by two models');
SELECT t.eq((SELECT count(*)::int FROM results WHERE model_name IN ('Missing')), 0, 'unknown model matches nothing');
SELECT t.eq((SELECT count(*)::int FROM results WHERE category IS NULL), 1000, 'rows without category');
SELECT t.eq((SELECT count(*)::int FROM results WHERE category IN ('Cardiology', 'Radiology', 'Neurology')), 9000,
    'category IN never matches NULL categories');
SELECT t.eq((SELECT count(*)::int FROM results WHERE is_vectorized = false), 5000, 'filter by pending vectorization');

SELECT t.eq((SELECT count(*)::int FROM results WHERE confidence_accuracy = 50.0),
            (SELECT count(*)::int FROM results WHERE confidence_accuracy >= 50.0 AND confidence_accuracy <= 50.0),
            '= equals an inclusive single-point range');
SELECT t.ok((SELECT bool_and(confidence_accuracy > 95) FROM results WHERE confidence_accuracy > 95), '> excludes the bound');
SELECT t.ok((SELECT min(confidence_accuracy) FROM results WHERE confidence_accuracy >= 95) = 95, '>= includes the bound');
SELECT t.ok((SELECT max(confidence_accuracy) FROM results WHERE confidence_accuracy < 5) < 5, '< excludes the bound');
SELECT t.ok((SELECT max(confidence_accuracy) FROM results WHERE confidence_accuracy <= 5) = 5, '<= includes the bound');
SELECT t.eq((SELECT count(*)::int FROM results WHERE confidence_accuracy >= 0 OR confidence_accuracy < 0), 9500,
    'NULL metrics never satisfy a comparison');
SELECT t.eq((SELECT count(*)::int FROM results WHERE confidence_accuracy > 60 AND confidence_accuracy < 40), 0,
    'an impossible range returns no rows instead of an error');

SELECT t.eq(
    (SELECT count(*)::int FROM results
      WHERE model_name IN ('CardioNet', 'NeuroSlice') AND category IN ('Cardiology') AND confidence_accuracy >= 95
        AND sensitivity > 10 AND recall <= 90),
    (SELECT count(*)::int FROM results r
      WHERE (r.model_name = 'CardioNet' OR r.model_name = 'NeuroSlice') AND r.category = 'Cardiology'
        AND r.confidence_accuracy >= 95 AND r.sensitivity > 10 AND r.recall <= 90),
    'combined filters behave as AND of each predicate');

-- Sorting
SELECT t.ok(
    (SELECT bool_and(prev <= cur) FROM (
        SELECT lag(confidence_accuracy) OVER (ORDER BY confidence_accuracy ASC NULLS LAST, id) AS prev, confidence_accuracy AS cur
          FROM results WHERE confidence_accuracy IS NOT NULL) s WHERE prev IS NOT NULL),
    'ascending metric sort is numeric');
SELECT t.eq(
    (SELECT array_agg(v ORDER BY v) FROM (VALUES (9.5::float8), (10.0), (100.0), (2.25)) x(v))::text,
    '{2.25,9.5,10,100}', 'double precision sorts numerically, not lexicographically');
SELECT t.ok(
    (SELECT bool_and(confidence_accuracy IS NULL) FROM (
        SELECT confidence_accuracy FROM results ORDER BY confidence_accuracy DESC NULLS LAST, id OFFSET 9500) s),
    'DESC NULLS LAST puts the 500 NULL metrics at the end');
SELECT t.ok(
    (SELECT confidence_accuracy IS NULL FROM results ORDER BY confidence_accuracy DESC, id LIMIT 1),
    'plain DESC puts NULLs first in Postgres, so the API must request NULLS LAST');
SELECT t.eq(
    (SELECT model_name FROM results ORDER BY model_name ASC, id LIMIT 1), 'CardioNet', 'text sort ascending');

-- Pagination
SELECT t.eq(
    (SELECT count(DISTINCT id)::int FROM (
        SELECT id FROM generate_series(0, 99) p,
               LATERAL (SELECT id FROM results ORDER BY confidence_accuracy DESC NULLS LAST, id LIMIT 100 OFFSET p * 100) pg) all_pages),
    10000, '100 pages of 100 cover every row exactly once with a tie-breaker');
SELECT t.eq(
    (SELECT count(*)::int FROM (SELECT id FROM results ORDER BY inserted_at DESC LIMIT 50 OFFSET 10000) s),
    0, 'offset past the end returns an empty page');
SELECT t.eq(
    (SELECT count(*)::int FROM (SELECT id FROM results WHERE model_name = 'ChestScan' ORDER BY inserted_at DESC LIMIT 50 OFFSET 2450) s),
    50, 'last page of a filtered result is full');
SELECT t.eq(
    (SELECT inserted_at FROM results ORDER BY inserted_at DESC LIMIT 1),
    timestamptz '2026-01-01T00:00:00Z' + interval '10000 minutes', 'newest row first by default');

-- Distinct option lists
SELECT t.eq((SELECT array_agg(m ORDER BY m) FROM (SELECT DISTINCT model_name AS m FROM results) d),
    ARRAY['CardioNet','ChestScan','DermaVision','NeuroSlice']::varchar[], 'distinct models');
SELECT t.eq((SELECT count(DISTINCT sub_category)::int FROM results WHERE category IN ('Cardiology')), 4,
    'sub categories narrowed by category');

-- Index usage (seqscan disabled so the planner reveals whether an index is usable)
SET enable_seqscan = off;
SELECT t.ok(t.plan($$SELECT id FROM results WHERE category = 'Cardiology'$$) LIKE '%idx_results_category_sub_category%',
    'category filter can use idx_results_category_sub_category');
SELECT t.ok(t.plan($$SELECT id FROM results WHERE sub_category = 'Lung'$$) LIKE '%idx_results_sub_category%',
    'sub category filter can use idx_results_sub_category');
SELECT t.ok(t.plan($$SELECT id FROM results WHERE confidence_accuracy >= 95 AND confidence_accuracy <= 99$$)
            LIKE '%idx_results_confidence_accuracy%', 'metric range can use its partial index');
SELECT t.ok(t.plan($$SELECT id FROM results WHERE recall > 50$$) LIKE '%idx_results_recall%', 'recall filter can use its index');
SELECT t.ok(t.plan($$SELECT id FROM results ORDER BY inserted_at DESC LIMIT 50$$) LIKE '%idx_results_inserted_at%',
    'default newest-first page can use idx_results_inserted_at');
SELECT t.ok(t.plan($$SELECT id FROM results WHERE is_vectorized = false ORDER BY inserted_at LIMIT 5$$)
            LIKE '%idx_results_%', 'worker batch query can use an index');
SELECT t.ok(t.plan($$SELECT id FROM results WHERE model_name = 'CardioNet' AND version = '1.1'$$)
            LIKE '%idx_results_model_version%', 'model + version lookup can use idx_results_model_version');
RESET enable_seqscan;
