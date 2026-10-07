# API overview

Source of truth: [`packages/openapi/openapi.yaml`](../packages/openapi/openapi.yaml).

## Auth

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/auth/login` | No | Returns JWT + user |
| GET | `/api/auth/me` | Bearer | Current user |

## Users

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/api/users` | Bearer | Superadmin: all except self; Admin: created members only; Member: 403 |
| POST | `/api/users` | Bearer | Superadmin: admin/member; Admin: member only |
| PATCH | `/api/users/{id}` | Bearer | Ownership rules; email must be unique; role change = superadmin only |
| POST | `/api/users/{id}/deactivate` | Bearer | Soft deactivate |
| DELETE | `/api/users/{id}` | Bearer | Superadmin only; if deleting an Admin, reassigns their members' `created_by` to the Superadmin first |

## Results (RAG ingestion)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/results` | `X-Ingest-Key` **or** Bearer (superadmin) | Writes raw JSON to disk, inserts metadata row (`is_vectorized=false`). The server sets `iterationId` to the local time as `yyyyMMddHHmmss`. Optional `category`, `subCategory`, and percentage metrics (`confidenceAccuracy`, `sensitivity`, `specificity`, `f1Score`, `recall`, `precision`) are stored on the row when present. Values are percentage points from 0 to 100. Admin and member JWTs receive 403. |
| GET | `/api/results` | Bearer | Filtered page. Query params apply in the database before sort and offset. Repeat `model`, `category`, or `subCategory` for exact matches. Response is `{ results, total, page, pageSize }`. Default sort is newest first (`sort=-insertedAt`), `page=1`, `pageSize=50` (max 100). |
| GET | `/api/results/filter-options` | Bearer | Distinct `models`, `categories`, and `subCategories`. Repeat `category` to limit subcategories to those categories. |
| GET | `/api/results/{id}` | Bearer | Metadata row |
| GET | `/api/results/{id}/payload` | Bearer | Raw JSON file content |
| POST | `/api/results/reindex` | Bearer (superadmin) | `TRUNCATE result_chunks` + flip all `is_vectorized=false`. Worker re-embeds. |

## Chat (RAG)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/chat` | Bearer | Body: `{ question, modelName?, version?, iterationId?, fromIso?, toIso?, history? }`. `history` is every user/assistant text in the current browser session so follow-ups like "them" resolve. A typed "today" or "yesterday" limits retrieval to that local day unless `fromIso`/`toIso` are already set. Returns `{ answer, citations }`. |

Implementation: `apps/api` (Java Spring Boot + Spring AI 1.0 + Ollama + pgvector).
