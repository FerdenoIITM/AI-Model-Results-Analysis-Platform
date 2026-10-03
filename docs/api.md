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
| POST | `/api/results` | Bearer **or** `X-Ingest-Key` | Writes raw JSON to disk, inserts metadata row (`is_vectorized=false`) |
| GET | `/api/results` | Bearer | Latest results first; `?limit=N` (default 100, max 500) |
| GET | `/api/results/{id}` | Bearer | Metadata row |
| GET | `/api/results/{id}/payload` | Bearer | Raw JSON file content |
| POST | `/api/results/reindex` | Bearer (superadmin) | `TRUNCATE result_chunks` + flip all `is_vectorized=false`. Worker re-embeds. |

## Chat (RAG)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/chat` | Bearer | Body: `{ question, modelName?, version?, iterationId?, fromIso?, toIso? }`. Returns `{ answer, citations }`. |

Implementation: `apps/api` (Java Spring Boot + Spring AI 1.0 + Ollama + pgvector).
