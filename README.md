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
| Runtime | Docker Compose: three environments (dev, test, prod) from one base file plus an override each |

## Project layout

```text
apps/api/            Spring Boot API (Maven modules: domain, persistence, application, web, security, bootstrap)
apps/web/            React frontend
infra/db/migrations/ SQL migrations (applied automatically to the test/prod databases)
infra/db/seeds/      How to create a superadmin by hand
infra/docker/        Dockerfiles and the web nginx template (serves the app, proxies /api)
env/                 Environment templates: dev.env.example, test.env.example, prod.env.example
docker-compose*.yml  Shared base + dev / test / prod overrides
packages/openapi/    OpenAPI spec (source of truth for the API)
docs/                Architecture, data model, and API notes
scripts/             Bootstrap, superadmin, hot-reload, and sample-data scripts
data/results/        Raw result JSON files of the dev environment
test/                Automated test suite: database SQL, API black-box, Playwright E2E, its own stack, runner
```

## Getting started

### 1. Prerequisites

- Docker with Docker Compose.
- PostgreSQL 16 with **pgvector** on the host (`sudo apt-get install postgresql-16-pgvector`), with one database per environment: `AMRAP-Dev`, `AMRAP-Test` and `AMRAP-Prod`. Alternatively, an environment can run its own database container (`COMPOSE_PROFILES=db`).

`./scripts/install-deps.sh` installs Docker and creates `.env.dev`. Add `--with-dev` to also install Java and Node for local development.

### 2. Environments

AMRAP has three isolated environments. Each has its own settings file, compose override, compose project (containers, network, volumes), ports and database:

| Environment | Start | Stop | Settings | Compose files | Database | Default web URL |
|-------------|-------|------|----------|---------------|----------|-----------------|
| dev | `make dev` | `make dev-down` | `.env.dev` | `docker-compose.yml` + `docker-compose.dev.yml` | host Postgres `AMRAP-Dev` | http://localhost:5173 |
| test | `make test` | `make test-down` | `.env.test` | `docker-compose.yml` + `docker-compose.test.yml` | host Postgres `AMRAP-Test` | http://localhost:5273 |
| prod | `make prod` | `make prod-down` | `.env.prod` | `docker-compose.yml` + `docker-compose.prod.yml` | host Postgres `AMRAP-Prod` | http://localhost |

The database is chosen by `POSTGRES_*` in the env file. To give an environment a disposable Postgres container instead, set `COMPOSE_PROFILES=db` and `POSTGRES_HOST=db`; the migrations then run automatically on its first start.

- **dev** mounts the source read-only: Vite hot reload for the web app, and the API restarts when `.java` files change. The API compiles inside its container, so it never writes into your `apps/api/*/target` folders. `make dev-local` is an alternative that runs the API with host Maven.
- **test** is production-like (built images) on its own ports, for checking a build before prod. It is not the automated test suite; that is `make check`.
- **prod** publishes only the web port. The API and Ollama are reachable only inside its compose network.

Other commands take `ENV=dev|test|prod`: `make logs ENV=test`, `make ps ENV=prod`, `make restart ENV=dev`, `make urls ENV=test`.

**Configuration lives only in the env files.** Hosts, ports, credentials, model names and tuning are read from `.env.<env>`. The code, Dockerfiles and compose files do not hardcode them, and Compose refuses to start if a required secret is missing. The env files are git-ignored; their templates are committed in `env/`.

### 3. Configure

```bash
make bootstrap ENV=dev     # also: ENV=test, ENV=prod
```

This copies `env/<env>.env.example` to `.env.<env>` and fills every `__GENERATE__` value (`JWT_SECRET`, `INGEST_API_KEY`, `SUPERADMIN_PASSWORD`) with a random secret. For dev, values from an old single `.env` are carried over. It never overwrites an existing file.

Review the file, especially:

- `POSTGRES_*`: the database connection. Fill in `POSTGRES_PASSWORD` with the password of `POSTGRES_USER` on your host Postgres.
- `WEB_PORT`, `API_PORT`, `DB_PORT`, `OLLAMA_PORT`: published host ports (must differ between environments that run at the same time).
- `TZ`: time zone for "today" in chat and for iteration IDs.
- `SUPERADMIN_*`: the first account created by `make superadmin`.

### 4. Set up the database

**Host Postgres (default):** create the environment's database (`AMRAP-Dev`, `AMRAP-Test` or `AMRAP-Prod`) and apply `V1__init.sql`, `V3__results_rag.sql` and `V4__result_metrics.sql` from `infra/db/migrations` in order (pgAdmin or `psql`). Then create the first superadmin:

```bash
make superadmin ENV=test      # uses SUPERADMIN_EMAIL / SUPERADMIN_PASSWORD from .env.test
```

For demo data in dev, see [`infra/db/seeds/README.md`](infra/db/seeds/README.md).

**Database container (`COMPOSE_PROFILES=db`):** the migrations run automatically the first time the container starts. Start the environment, then run `make superadmin ENV=<env>`.

### 5. Run

```bash
make dev                # or: make test / make prod
make logs ENV=dev       # follow logs
make dev-down           # stop (data is kept)
```

The browser always calls the API at `/api` on the same address as the page; the web server (nginx, or Vite in dev) forwards it to the API. `make test` and `make prod` rebuild the images, so re-run them after code changes.

On the first start the `ollama-init` container downloads the models (about 2.3 GB) into the shared volume named by `OLLAMA_MODELS_VOLUME`; every environment reuses it. Chat and vectorization start working once it logs `Models ready.`

## Submitting results

Send `POST /api/results` with the `X-Ingest-Key` header (`INGEST_API_KEY` from the environment's env file). Only `modelName`, `version`, and `payload` are required. `payload` can be any JSON. Metrics are percentages from 0 to 100. Use the environment's web address, for example `http://localhost:5173` for dev:

```bash
curl -X POST http://localhost:5173/api/results \
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
AMRAP_API_BASE=http://localhost:5173/api python3 scripts/ingest_sample_results.py
```

A superadmin can also submit from the website (**Submit result** on the Results page). Admin and member tokens are rejected with 403.

## Sharing on your network

Other machines on your LAN open `http://<your-ip>:<WEB_PORT>` of the environment (for example `http://<your-ip>:5173` for dev). Nothing needs to be rebuilt or reconfigured: the page calls `/api` on the address it was loaded from, and the web server forwards it to the API.

## Testing

The automated test suite is separate from the three environments: it starts its own disposable stack and never touches dev, test or prod data. Run everything with:

```bash
make check
```

You need Docker, Java 21 and Maven. Node is not needed on the host: frontend, API and E2E tests run in containers. The first run downloads images and dependencies, so it is slow; later runs reuse caches.

`make check` runs six suites, prints a table of passed, failed and skipped tests with coverage locations, and exits non-zero if anything fails. `FAIL` means tests failed. `ERROR` means the suite stopped before its tests finished, for example a compile error. Its counts are then missing or partial, and the first errors from its log are printed under the table. Logs and reports go to `test/reports/`. Only one `make check` can run at a time.

| Target | What it tests | Where the tests live |
|--------|---------------|----------------------|
| `make check-frontend` | React pages and components with mocked HTTP (Vitest, Testing Library, MSW), plus a type-check | `apps/web/src/**/*.test.ts(x)` |
| `make check-backend` | Services, filter parsing, JWT and auth filters (JUnit, Mockito) | `apps/api/*/src/test/java/**/*Test.java` |
| `make check-integration` | The Spring app against real Postgres + pgvector (Testcontainers): repository queries, every endpoint, security, database failure, concurrency | `apps/api/api-bootstrap/src/test/java/**/*IT.java` |
| `make check-db` | The SQL schema directly: constraints, foreign keys, the trigger, indexes, and filter/sort/paging on 10,000 rows | `test/database/` |
| `make check-api` | The running API over HTTP: status codes, response shapes, role matrix, invalid input, CORS, chat without an LLM | `test/api/` |
| `make check-e2e` | The site in Chromium (Playwright): the results filter flow, sorting, pagination, Clear filters, the submit modal, navigation, user management, chat errors | `test/e2e/` |

`check-api` and `check-e2e` start an isolated stack from `test/docker-compose.test.yml` (compose project `amrap-test-suite`, ports 15432, 18080 and 18081). It has its own database on tmpfs, test-only accounts from `test/fixtures/seed.sql`, and no Ollama. The stack is torn down afterwards; use `KEEP_STACK=1 make check-e2e` to keep it, or `make check-stack-up` / `make check-stack-down` to manage it yourself.

Coverage reports: `apps/web/coverage/index.html` (frontend) and `apps/api/api-bootstrap/target/site/jacoco-aggregate/index.html` (backend unit + integration).

## API

| Area | Endpoints |
|------|-----------|
| Auth | `POST /api/auth/login`, `GET /api/auth/me` |
| Users | `GET/POST /api/users`, `PATCH /api/users/{id}`, `POST /api/users/{id}/deactivate`, `DELETE /api/users/{id}` |
| Results | `POST /api/results`, `GET /api/results` (filter, sort, page), `GET /api/results/filter-options`, `GET /api/results/{id}`, `GET /api/results/{id}/payload`, `POST /api/results/reindex` |
| Chat | `POST /api/chat` |

Details: [`docs/api.md`](docs/api.md) and the OpenAPI spec in [`packages/openapi/openapi.yaml`](packages/openapi/openapi.yaml). See also [`docs/architecture.md`](docs/architecture.md) and [`docs/data-model.md`](docs/data-model.md).
