.PHONY: help up down logs ps bootstrap seed prod deps deps-dev dev dev-down dev-logs dev-docker

help:
	@echo "AMRAP — common commands"
	@echo "  make deps       - install Docker (+ optional: make deps-dev)"
	@echo "  make bootstrap  - prepare local env from .env.example"
	@echo "  make up         - packaged api + web images (rebuild on code change)"
	@echo "  make dev        - hot-reload (web in Docker node, API on host Maven)"
	@echo "  make dev-down   - stop hot-reload web container"
	@echo "  make dev-docker - hot-reload fully in Compose (needs Docker Hub for maven image)"
	@echo "  make down       - stop packaged compose stack"
	@echo "  make logs       - tail packaged compose logs"
	@echo "  make ps         - list running services"
	@echo "  make seed       - run demo data seed script"
	@echo "  make prod       - start with nginx (prod profile)"

deps:
	@./scripts/install-deps.sh

deps-dev:
	@./scripts/install-deps.sh --with-dev

bootstrap:
	@./scripts/bootstrap.sh

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f

ps:
	docker compose ps

seed:
	@./scripts/seed-demo-data.sh

prod:
	docker compose --profile prod up -d --build

# Hot-reload without pulling maven:* (avoids Docker Hub DNS failures).
# Requires once: sudo apt-get install -y maven inotify-tools
dev:
	@chmod +x scripts/dev-hotreload.sh
	@./scripts/dev-hotreload.sh

dev-down:
	-docker rm -f amrap-web-dev

dev-logs:
	-docker logs -f amrap-web-dev

# Optional: full Compose hot-reload (needs network to pull maven:3.9-eclipse-temurin-21 once)
dev-docker:
	@docker compose down 2>/dev/null || true
	docker compose -p amrap-dev -f docker-compose.dev.yml up --build
