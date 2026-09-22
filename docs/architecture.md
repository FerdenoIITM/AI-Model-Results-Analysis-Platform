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

Details will be expanded as modules are implemented.
