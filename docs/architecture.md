# Architecture

High-level flow:

```text
AI/ML Models → API (Java) → Validation/Processing → PostgreSQL
                                                      ↓
Web Dashboard ← Analytics/Comparison ←───────────────┘
       ↓
  LLM NL queries (api-llm / Spring AI)
```

## Components

- **apps/api** — Spring Boot multi-module backend (ingestion, auth, analytics, LLM).
- **apps/web** — React dashboard for visualization and NL interaction.
- **infra/db** — Schema migrations and seeds.
- **services/llm** — Optional future extraction of the LLM worker.
- **packages/openapi** — Shared API contracts.

## Runtime and environments

Each environment (dev, test, prod) is one Compose project built from `docker-compose.yml` plus `docker-compose.<env>.yml`, configured only by `.env.<env>` (templates in `env/`).

```text
Browser ──▶ web (nginx; Vite in dev) ──/api──▶ api ──▶ Postgres + pgvector (host database per environment, or a container with profile db)
                                               └──▶ ollama (models in a volume shared by all environments)
```

- The browser only knows the web address; `/api` is proxied to `API_UPSTREAM`, so no API host, IP or port is built into the frontend.
- Hosts, ports, credentials, model names, RAG tuning and the time zone come from environment variables. Secrets have no defaults: Compose and the API refuse to start without them.
- The automated test suite (`make check`) runs its own stack (`test/docker-compose.test.yml`, project `amrap-test-suite`), separate from the three environments.
