-- V3__results_rag.sql
-- AMRAP: AI model result ingestion + RAG vector store.
--
-- Prereq: pgvector extension must be installable on the server.
--   * Containerized DB: use pgvector/pgvector:pg16 image (compose local-db profile).
--   * Host Postgres (Debian/Ubuntu):  sudo apt-get install postgresql-16-pgvector
--
-- Run via pgAdmin Query Tool or psql against the AMRAP database.

CREATE EXTENSION IF NOT EXISTS vector;

-- 1) Metadata row per result submission. Raw JSON lives on disk at json_path.
CREATE TABLE results (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_name      VARCHAR(255) NOT NULL,
    version         VARCHAR(100) NOT NULL,
    iteration_id    VARCHAR(255) NOT NULL,
    json_path       TEXT         NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    content_hash    VARCHAR(128) NOT NULL,
    inserted_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_vectorized   BOOLEAN      NOT NULL DEFAULT FALSE,
    vectorized_at   TIMESTAMPTZ  NULL,
    created_by      UUID         NULL,

    CONSTRAINT fk_results_created_by
        FOREIGN KEY (created_by)
        REFERENCES users (id)
        ON DELETE SET NULL
        ON UPDATE CASCADE
);

CREATE INDEX idx_results_model_version  ON results (model_name, version);
CREATE INDEX idx_results_inserted_at    ON results (inserted_at DESC);
CREATE INDEX idx_results_is_vectorized  ON results (is_vectorized) WHERE is_vectorized = FALSE;

COMMENT ON TABLE  results               IS 'Metadata rows for AI model result submissions. Raw JSON on disk at json_path.';
COMMENT ON COLUMN results.json_path     IS 'Relative path under RESULTS_STORAGE_DIR to the full JSON submission.';
COMMENT ON COLUMN results.is_vectorized IS 'Set TRUE by the worker after chunks are embedded. Flip to FALSE to force re-index.';
COMMENT ON COLUMN results.content_hash  IS 'SHA-256 of the JSON file bytes (hex).';

-- 2) Vector store table laid out for Spring AI's PgVectorStore.
--    Dimensions = 768 (matches nomic-embed-text). If you switch embedding model,
--    DROP and recreate this table with the new dimension.
CREATE TABLE result_chunks (
    id          UUID    PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   VECTOR(768)
);

-- HNSW ANN index on the embedding (cosine distance matches our Spring AI config).
CREATE INDEX idx_result_chunks_embedding
    ON result_chunks USING hnsw (embedding vector_cosine_ops);

-- B-tree index on the metadata result_id so we can clean up chunks per result.
CREATE INDEX idx_result_chunks_result_id
    ON result_chunks ((metadata ->> 'result_id'));

COMMENT ON TABLE  result_chunks          IS 'Spring AI vector store. Metadata JSON holds result_id, chunk_index, model_name, version, iteration_id.';
COMMENT ON COLUMN result_chunks.metadata IS 'Keys: result_id, chunk_index, model_name, version, iteration_id, inserted_at.';
