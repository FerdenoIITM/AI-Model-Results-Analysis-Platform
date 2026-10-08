-- result_chunks: vector dimension, jsonb metadata, primary key, similarity query, truncate.

TRUNCATE users, results, result_chunks CASCADE;

CREATE OR REPLACE FUNCTION t.vec(seed float8, dims int DEFAULT 768) RETURNS vector AS $$
    SELECT ('[' || string_agg(((i % 7) * seed + 0.01)::text, ',') || ']')::vector
      FROM generate_series(1, dims) AS i;
$$ LANGUAGE sql;

SELECT t.lives($$INSERT INTO result_chunks (id, content, metadata, embedding)
                 VALUES (gen_random_uuid(), 'recall: 91', '{"result_id":"r1","chunk_index":0}', t.vec(1))$$,
    '768-dimension embedding inserts');
SELECT t.throws($$INSERT INTO result_chunks (id, embedding) VALUES (gen_random_uuid(), t.vec(1, 3))$$,
    '22000', 'wrong embedding dimension is rejected');
SELECT t.throws($$INSERT INTO result_chunks (content) VALUES ('no id')$$, '23502', 'id is required (no default)');
SELECT t.throws($$INSERT INTO result_chunks (id, metadata) VALUES (gen_random_uuid(), '{not json')$$,
    '22P02', 'invalid JSON metadata is rejected');
SELECT t.throws(format($$INSERT INTO result_chunks (id) VALUES (%L)$$, (SELECT id FROM result_chunks LIMIT 1)),
    '23505', 'duplicate chunk id is rejected');
SELECT t.lives($$INSERT INTO result_chunks (id) VALUES (gen_random_uuid())$$, 'content, metadata and embedding may be NULL');

INSERT INTO result_chunks (id, content, metadata, embedding)
SELECT gen_random_uuid(), 'chunk ' || i, jsonb_build_object('result_id', 'r' || (i % 5), 'chunk_index', i), t.vec(i)
  FROM generate_series(2, 50) AS i;

SELECT t.eq(
    (SELECT count(*)::int FROM result_chunks WHERE metadata ->> 'result_id' = 'r1'),
    10, 'chunks can be looked up by metadata result_id');

SELECT t.eq(
    (SELECT content FROM result_chunks WHERE embedding IS NOT NULL ORDER BY embedding <=> t.vec(1) LIMIT 1),
    'recall: 91', 'cosine similarity search returns the nearest chunk first');

SET enable_seqscan = off;
SELECT t.ok(t.plan($$SELECT id FROM result_chunks WHERE metadata ->> 'result_id' = 'r1'$$) LIKE '%idx_result_chunks_result_id%',
    'result_id lookup can use idx_result_chunks_result_id');
SELECT t.ok(t.plan(format($$SELECT id FROM result_chunks ORDER BY embedding <=> %L::vector LIMIT 5$$, t.vec(2)::text))
            LIKE '%idx_result_chunks_embedding%',
    'nearest-neighbour search can use the HNSW index');
RESET enable_seqscan;

TRUNCATE result_chunks;
SELECT t.eq((SELECT count(*)::int FROM result_chunks), 0, 'TRUNCATE (used by reindex) empties the vector store');
