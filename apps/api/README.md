# apps/api — Java Spring Boot backend

Multi-module Maven project. Modules:

| Module | Role |
|--------|------|
| api-bootstrap | Spring Boot entrypoint |
| api-web | REST controllers / DTOs |
| api-domain | Domain entities |
| api-application | Use-cases (ingest, validate, query, analytics) |
| api-persistence | JPA / repositories |
| api-security | Auth & access control |
| api-llm | Spring AI NL interface |

Scaffold only — implement features in later milestones.
