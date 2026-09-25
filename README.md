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

4. Choose how to run:

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

| Role | User Management |
|------|-----------------|
| superadmin | Full CRUD (cannot create superadmin via UI/API) |
| admin | Members they created only |
| member | No User Management (403 on `/api/users`) |
