# apps/web — React dashboard

## Dev

Run it with the rest of the stack: `make dev` from the repository root (Vite with hot reload in a container).

Standalone:

```bash
cd apps/web
npm install
VITE_PROXY_TARGET=http://localhost:<API_PORT> npm run dev
```

The app calls `/api` on its own origin. Vite proxies it to `VITE_PROXY_TARGET` (default `http://localhost:8080`).

## Docker

Built by `make test` / `make prod` (see `infra/docker/web.Dockerfile`). In the image, nginx serves the build and proxies `/api` to `API_UPSTREAM`.
