# AI Model Results Analysis Platform (AMRAP)

Platform for collecting, storing, monitoring, and analyzing results from AI/deep learning models.

## Milestone 2 — Auth + User Management

- Java Spring Boot API with JWT login and role-based user APIs
- React dashboard: Login → Dashboard → User Management (Superadmin/Admin)
- Docker Compose runs **api** + **web**; PostgreSQL is your **local** instance (pgAdmin)

## Quick start

1. Ensure `users` table exists and Superadmin is inserted (see `infra/db/`).
2. Install host tools (Docker) and create `.env` if needed:

```bash
./scripts/install-deps.sh
# optional local Java/Node: ./scripts/install-deps.sh --with-dev
```

3. Edit `.env` with your local Postgres credentials and a long `JWT_SECRET`.

4. Start API + web:

```bash
make up
```

- Web: http://localhost:5173  
- API: http://localhost:8080/api  

From Docker, the API reaches host Postgres via `host.docker.internal` (set in compose).

## Local dev (without Docker)

```bash
# API
cd apps/api && mvn -pl api-bootstrap -am spring-boot:run

# Web
cd apps/web && npm install && npm run dev
```

For local API, set `POSTGRES_HOST=localhost` in the environment.

## Roles

| Role | User Management |
|------|-----------------|
| superadmin | Full CRUD (cannot create superadmin via UI/API) |
| admin | Members they created only |
| member | No User Management (403 on `/api/users`) |
