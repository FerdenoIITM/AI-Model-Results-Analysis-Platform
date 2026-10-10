# Data model

Schema migrations live in [`infra/db/migrations/`](../infra/db/migrations/). Domain types will live in `apps/api/api-domain` when the Java layer is implemented.

## Current schema (V1)

### Entity: User

Application identity for authentication and role-based authorization.

| Column | Type | Notes |
|--------|------|--------|
| `id` | `UUID` | Primary key |
| `email` | `VARCHAR(320)` | Unique login identifier; stored lowercase |
| `password_hash` | `VARCHAR(255)` | Bcrypt/Argon2 hash only (hashed in the app) |
| `role` | `user_role` ENUM | `superadmin` \| `admin` \| `member` |
| `display_name` | `VARCHAR(120)` | Display label in UI |
| `created_by` | `UUID` FK → `users.id` | Creator; `NULL` for bootstrap Superadmin(s) |
| `is_active` | `BOOLEAN` | Soft deactivation; inactive users cannot log in |
| `last_login_at` | `TIMESTAMPTZ` | Last successful login |
| `password_changed_at` | `TIMESTAMPTZ` | For future password-policy checks |
| `created_at` / `updated_at` | `TIMESTAMPTZ` | Audit; `updated_at` via trigger |

### Roles

| Role | Meaning |
|------|---------|
| `superadmin` | Full access; manage all users; created at setup (not via normal UI) |
| `admin` | Create/manage only Members they created (`created_by = self`) |
| `member` | Read-only; no User Management |

### Ownership (`created_by`)

```text
Superadmin (created_by NULL)
   ├── Admin A
   │      ├── Member A1
   │      └── Member A2
   └── Admin B
          ├── Member B1
          └── Member B2
```

- Backend authorization uses `role` + `created_by` (not UI alone).
- Admin A manages rows where `created_by = Admin A.id` and `role = 'member'`.
- Superadmin may manage every row.
- Prefer `is_active = FALSE` over `DELETE`; FK is `ON DELETE RESTRICT` so creators with dependents cannot be hard-deleted.

### Authentication vs authorization

- **Authentication:** email + `password_hash` (+ `is_active`).
- **Authorization:** `role` and ownership via `created_by`.

## V3 — Results + RAG vector store

See [`infra/db/migrations/V3__results_rag.sql`](../infra/db/migrations/V3__results_rag.sql).

### Entity: Result

One row per ingested AI model result. The raw JSON lives on disk at
`${RESULTS_STORAGE_DIR}/<uuid>.json`. Postgres only stores metadata.

| Column | Type | Notes |
|--------|------|--------|
| `id` | `UUID` | Primary key |
| `model_name` | `VARCHAR(255)` | Logical model name (e.g. `M1`) |
| `version` | `VARCHAR(100)` | Model version string (e.g. `1.0.0`) |
| `iteration_id` | `VARCHAR(255)` | Server-assigned local timestamp `yyyyMMddHHmmss` (for example `20261006135322`). Older rows keep the id they were stored with. |
| `json_path` | `TEXT` | Relative path under storage dir |
| `size_bytes` | `BIGINT` | Payload size |
| `content_hash` | `VARCHAR(128)` | SHA-256 of file bytes |
| `inserted_at` | `TIMESTAMPTZ` | When API stored the row |
| `is_vectorized` | `BOOLEAN` | `FALSE` until worker embeds; flip to `FALSE` to force reindex |
| `vectorized_at` | `TIMESTAMPTZ` | When worker last embedded |
| `created_by` | `UUID` FK → `users.id` | NULL for machine ingest with API key |

### V4 — filter columns on `results`

See [`infra/db/migrations/V4__result_metrics.sql`](../infra/db/migrations/V4__result_metrics.sql).

Columns are nullable so rows ingested before V4 stay valid. They are not backfilled. New submissions may omit them; the website form sends them.

Metrics are percentage points in `[0, 100]` (`97.5` means 97.5%), stored as `DOUBLE PRECISION`. The same number is used in the database, API, and UI. The free-form payload field `confidence` is unrelated and stays inside the JSON file.

| Column | Type | Null | Constraint |
|--------|------|------|------------|
| `category` | `VARCHAR(120)` | yes | null, or trimmed and non-empty |
| `sub_category` | `VARCHAR(120)` | yes | same |
| `confidence_accuracy` | `DOUBLE PRECISION` | yes | null, or `0 <= value <= 100` |
| `sensitivity` | `DOUBLE PRECISION` | yes | same |
| `specificity` | `DOUBLE PRECISION` | yes | same |
| `f1_score` | `DOUBLE PRECISION` | yes | same |
| `recall` | `DOUBLE PRECISION` | yes | same |
| `precision` | `DOUBLE PRECISION` | yes | same |

`category` and `sub_category` stay strings on the row. They are attributes of a submission, not a separate taxonomy.

Indexes:

- `(category, sub_category)` where `category` is not null — category filters, and category plus subcategory
- `(sub_category)` where not null — subcategory without a category
- one btree per metric where that metric is not null — independent range filters, combined with bitmap AND

### Entity: result_chunks (Spring AI pgvector store)

Managed by Spring AI's `PgVectorStore`. Each ingested result produces N chunks.

| Column | Type | Notes |
|--------|------|--------|
| `id` | `UUID` | Primary key, assigned by Spring AI |
| `content` | `TEXT` | Flattened chunk text |
| `metadata` | `JSONB` | `{ result_id, chunk_index, model_name, version, iteration_id, inserted_at }` |
| `embedding` | `VECTOR(768)` | HNSW index with cosine distance (matches `nomic-embed-text`) |

Design notes:
- Raw JSON is stored on disk, **not** in Postgres, to keep DB backups small for large payloads.
- Chunks + embeddings are stored in Postgres because similarity search runs there (pgvector).
- `is_vectorized` is the only operational knob needed to force re-embedding; the superadmin
  endpoint `POST /api/results/reindex` truncates `result_chunks` and resets the flag.

## Planned later

| Entity | Purpose |
|--------|---------|
| Model | Registered AI/ML model metadata |
| Experiment | A run/configuration of a model |
| Metric | Now stored on `results` (V4). No separate metric table. |

## Bootstrap

See [`infra/db/seeds/README.md`](../infra/db/seeds/README.md) for creating the first Superadmin securely.

## Required Postgres extensions

| Extension | Used by | Install (Debian/Ubuntu host) |
|-----------|---------|------------------------------|
| `pgcrypto` | `crypt()` in `make superadmin` (created by the script if missing) | ships with Postgres |
| `vector` | pgvector for RAG | `sudo apt-get install postgresql-16-pgvector` |

Each environment has its own host database (`AMRAP-Dev`, `AMRAP-Test`, `AMRAP-Prod`). An environment started with Compose profile `db` uses the `pgvector/pgvector:pg16` image instead, which ships both extensions.
