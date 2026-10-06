# AI Model Results Analysis Platform (AMRAP)

Platform for collecting, storing, monitoring, and analyzing results from AI/deep learning models.

## Milestone 3 — Result Ingestion + RAG Chat

- Machine endpoint `POST /api/results` ingests arbitrary JSON (`X-Ingest-Key`, or a superadmin JWT) → file on disk + metadata row
- Background worker chunks the JSON, embeds via Ollama, writes into pgvector (`result_chunks`)
- `POST /api/chat` answers natural-language questions via RAG over the vector store
- UI: new **Results** page (list + submit + reindex) and **Chat** page (filters + citations)

Earlier milestones (still present):
- Milestone 2: Auth (JWT) and User Management (Superadmin/Admin/Member)
- Docker Compose runs **api** + **web** + **ollama**; Postgres is your local instance by default

## Quick start

1. Make sure your Postgres has `pgvector` installed. Options:
   - Host Postgres (Debian/Ubuntu): `sudo apt-get install postgresql-16-pgvector`
   - Or use the containerized DB: `docker compose --profile local-db up db` (image already includes pgvector)
2. Apply SQL migrations in order (via pgAdmin or `psql`): `V1__init.sql`, `V2__drop_login_lockout.sql`, `V3__results_rag.sql`, `V4__result_metrics.sql` — see [`infra/db/migrations/`](infra/db/migrations/). Seed the first Superadmin (see [`infra/db/seeds/`](infra/db/seeds/)).
3. Install host tools (Docker) and create `.env` if needed:

```bash
./scripts/install-deps.sh
# optional local Java/Node: ./scripts/install-deps.sh --with-dev
```

4. Edit `.env` with your Postgres credentials, a long `JWT_SECRET`, and a fresh `INGEST_API_KEY`.

5. Choose how to run:

### Day-to-day coding (hot reload) — recommended

```bash
# Once (host tools for the API):
sudo apt-get update && sudo apt-get install -y maven inotify-tools
# or: make deps-dev

make down    # free ports if make up is running
make dev
```

- Web: http://localhost:5173 — Vite HMR (uses local `node:20-alpine` image; no Maven image pull)
- API: http://localhost:8080 — runs on the host with Maven; restarts when you change `.java` files
- Stop with `Ctrl+C`, or `make dev-down` for the web container

If `make dev-docker` fails with `registry-1.docker.io` DNS timeout, use `make dev` instead (does not need the Maven Docker image). When Docker Hub works, you can optionally use `make dev-docker` for an all-in-Compose setup.

### Packaged run (like a release)

```bash
make up
```

Rebuilds Docker images from Dockerfiles. Use this for a production-like check, not for every save.

- Web: http://localhost:5173  
- API: http://localhost:8080/api  

From Docker, the API reaches host Postgres via `host.docker.internal` (set in compose).

## Local tools without Docker Compose

```bash
# API
cd apps/api && mvn -pl api-bootstrap -am spring-boot:run

# Web
cd apps/web && npm install && npm run dev
```

For local API on the host, set `POSTGRES_HOST=localhost` in the environment.

## Environments (later)

Today: **dev hot-reload** (`make dev`) vs **packaged** (`make up` / optional `make prod`).

Planned next: separate env configs (e.g. `.env.dev`, `.env.test`, `.env.prod`) and compose overlays for **dev / test / prod** without changing app code. Keep secrets in env files, not in the repo.

## Roles

| Role | User Management | Results & Chat |
|------|-----------------|----------------|
| superadmin | Full CRUD (cannot create superadmin via UI/API) | Submit + view + chat + `POST /api/results/reindex` |
| admin | Members they created only | View + chat |
| member | No User Management (403 on `/api/users`) | View + chat |

## RAG workflow

1. **Ingest** — `POST /api/results` with header `X-Ingest-Key: <INGEST_API_KEY>` (normal path for Postman, Python, and other clients) or a superadmin JWT (website fallback) and body `{ modelName, version, payload }`. The server sets `iterationId` to the local time as `yyyyMMddHHmmss`. Admin and member JWTs receive 403. The raw JSON is written to `./data/results/<year>/<month>/<day>/<uuid>.json` and a row is inserted with `is_vectorized=false`.
2. **Vectorize (automatic)** — The background worker polls every `RAG_WORKER_DELAY_MS`, chunks the JSON, calls Ollama's embedding model (`nomic-embed-text`), writes chunks + vectors into `result_chunks`, and flips the row to `is_vectorized=true`.
3. **Chat** — `POST /api/chat` with `{ question, modelName?, version?, fromIso?, toIso? }`. The service filters by metadata, runs HNSW similarity in pgvector, and asks the Ollama chat model (`llama3.2:3b`) to answer using the retrieved chunks. Returns `{ answer, citations[] }`.
4. **Reindex** — Superadmin can call `POST /api/results/reindex` from the Results page: it truncates `result_chunks` and flips every row to `is_vectorized=false`; the worker re-embeds everything.

First boot downloads the Ollama models (~2 GB for `llama3.2:3b` + ~274 MB for `nomic-embed-text`); progress is in `amrap-ollama-init` logs.

Example ingest:

```bash
curl -X POST http://localhost:8080/api/results \
  -H "Content-Type: application/json" \
  -H "X-Ingest-Key: $INGEST_API_KEY" \
  -d '{
    "modelName": "M1",
    "version": "1.0.0",
    "payload": {
      "confidence": 0.93,
      "classes": ["cat", "dog"],
      "notes": "trained on 10k images; overfits past epoch 7"
    }
  }'
```
