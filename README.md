# AI Model Results Analysis Platform (AMRAP)

AMRAP is a web platform for collecting, browsing, and analyzing the results produced by AI / deep learning models. Training scripts, notebooks, or any HTTP client submit a model's results as JSON; AMRAP stores them, lets your team filter and compare them in a table, and answers natural-language questions about them using a local LLM (RAG). Everything runs on your own machine — no data leaves it.

## What you can do

### Model Results page

- **Browse every submitted result** in one table: Model, Version, Iteration, Category, Sub Category, the six performance metrics (Confidence/Accuracy, Sensitivity, Specificity, F1 Score, Recall, Precision), file size, insert time, and vectorization status.
- **Sort** by any text column, by insert time, or by any metric. Metric headers cycle through ascending → descending → default order, and sorting is numeric.
- **Filter** with the Filters panel (hidden until you open it):
  - Model, Category, and Sub Category multi-select dropdowns, filled from the models and categories actually in the database.
  - Vectorized: All / Yes / No.
  - Each metric takes an operator (`>`, `>=`, `<`, `<=`) and a value from 0 to 100, plus an optional second condition for ranges, e.g. F1 Score between 80 and 99.
  - **Clear filters** resets everything at once.
- Filters, sort, and paging live in the URL, so a filtered view can be bookmarked or shared.
- **View JSON** shows the raw payload that was submitted for a result.
- **Submit result** (superadmin) adds a result from the browser.
- **Reindex vectors** (superadmin) rebuilds the chat's search index from scratch.

### Ask AMRAP (chat)

- Ask questions in plain English, e.g. *"Which CardioNet version has the best sensitivity?"* or *"What results do we have from today?"*
- Answers come with **citations** pointing to the results they were based on.
- Optionally narrow the search by Model, Version, or Date (All time / Today).
- Follow-up questions work: the conversation so far is sent along, so "compare them" refers to the previous answer.
- Answers about many results at once (averages, counts) can be approximate. For exact numbers use the Results table filters.

### Users and access

Accounts are created by administrators; there is no self sign-up. Login uses JWT.

| Role | User Management | Results & Chat |
|------|-----------------|----------------|
| superadmin | Create, edit, deactivate, and delete admins and members | View, chat, submit results, reindex |
| admin | Manage only the members they created | View and chat |
| member | No access | View and chat |

## How it works

```text
 Training script / Postman / Website
              │  POST /api/results  (JSON + metrics)
              ▼
      ┌───────────────┐   raw JSON file    ┌──────────────────────┐
      │  Spring Boot  │ ─────────────────▶ │ data/results/<id>.json│
      │      API      │   metadata row     └──────────────────────┘
      │               │ ─────────────────▶  PostgreSQL  (results table)
      └───────────────┘
              │  background worker (every few seconds)
              ▼
   chunk JSON → embed with Ollama (nomic-embed-text) → pgvector (result_chunks)
              │
              ▼
   POST /api/chat → similarity search → Ollama LLM (llama3.2:3b) → answer + citations
```

1. **Ingest:** a client posts a result. The raw JSON is saved to `data/results/<uuid>.json`, and its metadata (model, version, category, metrics) goes into Postgres. The server assigns the iteration ID from the current time (`yyyyMMddHHmmss`).
2. **Vectorize:** a background worker picks up new results, splits the JSON into chunks, embeds them with Ollama, and stores the vectors in pgvector. The table's Vectorized column switches from *pending* to *yes*.
3. **Ask:** the chat filters by metadata, finds the most similar chunks, and has the local LLM write an answer using only those chunks.

## Tech stack

| Layer | Technology |
|-------|------------|
| Frontend | React 18, TypeScript, Vite, React Router |
| Backend | Java 21, Spring Boot 3, Spring Security (JWT), Spring Data JPA, Spring AI |
| Database | PostgreSQL 16 with the pgvector extension |
| LLM / embeddings | Ollama running `llama3.2:3b` (chat) and `nomic-embed-text` (embeddings) |
| Runtime | Docker Compose (api, web, ollama; optional Postgres and nginx) |

## Project layout

```text
apps/api/            Spring Boot API (Maven modules: domain, persistence, application, web, security, bootstrap)
apps/web/            React frontend
infra/db/migrations/ SQL migrations (applied manually)
infra/db/seeds/      How to create the first superadmin
infra/docker/        Dockerfiles and the web nginx config
packages/openapi/    OpenAPI spec (source of truth for the API)
docs/                Architecture, data model, and API notes
scripts/             Setup, hot-reload, and sample-data scripts
data/results/        Raw result JSON files (bind-mounted into the API container)
test/                Cross-stack tests: database SQL, API black-box, Playwright E2E, test stack, runner
```

## Getting started

### 1. Prerequisites

- Docker with Docker Compose.
- PostgreSQL 16 with **pgvector**. Use either:
  - your host Postgres, after `sudo apt-get install postgresql-16-pgvector`, or
  - the bundled container: `docker compose --profile local-db up -d db`.

`./scripts/install-deps.sh` installs Docker and creates `.env` from `.env.example`. Add `--with-dev` to also install Java and Node for local development.

### 2. Configure

Edit `.env`:

- `POSTGRES_*`: your database connection.
- `JWT_SECRET`: a long random string.
- `INGEST_API_KEY`: a fresh secret that scripts use to submit results.
- `VITE_API_BASE_URL`: the API URL the browser should call (see [Sharing on your network](#sharing-on-your-network)).

### 3. Set up the database

Apply the migrations in order (pgAdmin or `psql`):

1. `infra/db/migrations/V1__init.sql`
2. `infra/db/migrations/V3__results_rag.sql`
3. `infra/db/migrations/V4__result_metrics.sql`

Then create the first superadmin by following [`infra/db/seeds/README.md`](infra/db/seeds/README.md).

### 4. Run

**Packaged (production-like):**

```bash
make up      # builds images and starts api + web + ollama
make down    # stop
make logs    # follow logs
```

- Web: http://localhost:5173
- API: http://localhost:8080/api

`make up` rebuilds the images, so re-run it after code changes.

**Development (hot reload):**

```bash
sudo apt-get install -y maven inotify-tools   # once
make down
make dev
```

The web app runs with Vite hot reload on http://localhost:5173. The API runs on the host with Maven and restarts when `.java` files change. Stop it with `Ctrl+C` and `make dev-down`.

On first boot the `amrap-ollama-init` container downloads the models (about 2.3 GB). Chat and vectorization start working once it logs `Models ready.`

## Submitting results

Send `POST /api/results` with the `X-Ingest-Key` header. Only `modelName`, `version`, and `payload` are required. `payload` can be any JSON. Metrics are percentages from 0 to 100.

```bash
curl -X POST http://localhost:8080/api/results \
  -H "Content-Type: application/json" \
  -H "X-Ingest-Key: $INGEST_API_KEY" \
  -d '{
    "modelName": "CardioNet",
    "version": "1.2.0",
    "category": "Cardiology",
    "subCategory": "Aortic Valve",
    "confidenceAccuracy": 97.5,
    "sensitivity": 94.2,
    "specificity": 98.1,
    "f1Score": 95.8,
    "recall": 94.2,
    "precision": 97.3,
    "payload": {
      "dataset": "echo-valves-v3",
      "epochs": 40,
      "notes": "overfits past epoch 30"
    }
  }'
```

To load a few demo results:

```bash
export INGEST_API_KEY=<your key>
python3 scripts/ingest_sample_results.py
```

A superadmin can also submit from the website (**Submit result** on the Results page). Admin and member tokens are rejected with 403.

## Sharing on your network

To let other machines on your LAN use the site, set `VITE_API_BASE_URL` in `.env` to your machine's address (for example `http://<your-ip>:8080/api`), then run `make up`. Others open `http://<your-ip>:5173`. The web container's nginx only serves the built site; it does not proxy `/api`, so the URL must point at the API itself.

If your shell already exports `VITE_API_BASE_URL`, it overrides `.env`. Unset it before building.

## Testing

Run everything with:

```bash
make test
```

You need Docker, Java 21 and Maven. Node is not needed on the host: frontend, API and E2E tests run in containers. The first run downloads images and dependencies, so it is slow; later runs reuse caches.

`make test` runs six suites, prints a table of passed, failed and skipped tests with coverage locations, and exits non-zero if anything fails. Logs and reports go to `test/reports/`.

| Target | What it tests | Where the tests live |
|--------|---------------|----------------------|
| `make test-frontend` | React pages and components with mocked HTTP (Vitest, Testing Library, MSW), plus a type-check | `apps/web/src/**/*.test.ts(x)` |
| `make test-backend` | Services, filter parsing, JWT and auth filters (JUnit, Mockito) | `apps/api/*/src/test/java/**/*Test.java` |
| `make test-integration` | The Spring app against real Postgres + pgvector (Testcontainers): repository queries, every endpoint, security, database failure, concurrency | `apps/api/api-bootstrap/src/test/java/**/*IT.java` |
| `make test-db` | The SQL schema directly: constraints, foreign keys, the trigger, indexes, and filter/sort/paging on 10,000 rows | `test/database/` |
| `make test-api` | The running API over HTTP: status codes, response shapes, role matrix, invalid input, CORS, chat without an LLM | `test/api/` |
| `make test-e2e` | The site in Chromium (Playwright): the results filter flow, sorting, pagination, Clear filters, the submit modal, navigation, user management, chat errors | `test/e2e/` |

`test-api` and `test-e2e` start an isolated stack from `test/docker-compose.test.yml` (compose project `amrap-test`, ports 15432, 18080 and 18081). It has its own database on tmpfs, test-only accounts from `test/fixtures/seed.sql`, and no Ollama, so your normal data and containers are never touched. The stack is torn down afterwards; use `KEEP_STACK=1 make test-e2e` to keep it, or `make test-stack-up` / `make test-stack-down` to manage it yourself.

Coverage reports: `apps/web/coverage/index.html` (frontend) and `apps/api/api-bootstrap/target/site/jacoco-aggregate/index.html` (backend unit + integration).

## API

| Area | Endpoints |
|------|-----------|
| Auth | `POST /api/auth/login`, `GET /api/auth/me` |
| Users | `GET/POST /api/users`, `PATCH /api/users/{id}`, `POST /api/users/{id}/deactivate`, `DELETE /api/users/{id}` |
| Results | `POST /api/results`, `GET /api/results` (filter, sort, page), `GET /api/results/filter-options`, `GET /api/results/{id}`, `GET /api/results/{id}/payload`, `POST /api/results/reindex` |
| Chat | `POST /api/chat` |

Details: [`docs/api.md`](docs/api.md) and the OpenAPI spec in [`packages/openapi/openapi.yaml`](packages/openapi/openapi.yaml). See also [`docs/architecture.md`](docs/architecture.md) and [`docs/data-model.md`](docs/data-model.md).
