# AI Model Results Analysis Platform (AMRAP)

Platform for collecting, storing, monitoring, and analyzing results from AI/deep learning models.

```text
AI/ML Models → API → Validation/Processing → Database → Analytics → Dashboard
                                              ↘ LLM (natural-language queries)
```

## Stack

| Layer | Technology |
|-------|------------|
| API, validation, auth, analytics, LLM orchestration | Java (Spring Boot + Spring AI) |
| Web dashboard | React + TypeScript (Vite) |
| Database | PostgreSQL |
| Orchestration | Docker Compose |

## Repository layout

```text
apps/api          Java Spring Boot multi-module backend
apps/web          React dashboard
services/llm      Reserved for a standalone LLM worker (optional later)
packages/openapi  Shared API contracts
infra/            Dockerfiles, DB migrations, nginx
docs/             Architecture and design notes
scripts/          Bootstrap and seed helpers
```

## Quick start (Docker)

```bash
cp .env.example .env
make up
```

- API: `http://localhost:8080`
- Web: `http://localhost:5173`
- DB: `localhost:5432`

With reverse proxy (prod profile):

```bash
make prod
```

## Status

Folder structure scaffold only — application logic, dependencies, and images will be added in later milestones.
