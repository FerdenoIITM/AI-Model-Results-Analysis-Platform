# apps/api — Java Spring Boot backend

## Modules

| Module | Role |
|--------|------|
| api-bootstrap | Entrypoint + `application.yml` |
| api-web | REST controllers |
| api-application | Auth + user management use-cases |
| api-persistence | JPA `users` mapping |
| api-security | JWT + BCrypt + filter chain |
| api-domain | `User`, `UserRole` |
| api-llm | Reserved (not wired yet) |

## Build / run

```bash
mvn -pl api-bootstrap -am package -DskipTests
mvn -pl api-bootstrap -am spring-boot:run
```

Requires env vars (or defaults): `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SECRET`.
