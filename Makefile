# AMRAP — environments (dev, test, prod) and the automated test suite (check*).
# Every environment reads its settings from .env.<env> (create with `make bootstrap ENV=<env>`).

ENV ?= dev
ENV_FILE = .env.$(ENV)
# Shell variables would override the env file in Compose, so every key the env file defines is unset first.
COMPOSE = env $$(sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/-u \1/p' $(ENV_FILE)) \
	docker compose --env-file $(ENV_FILE) -f docker-compose.yml -f docker-compose.$(ENV).yml
env_value = $$(sed -n 's/^$(1)=//p' $(ENV_FILE) | tail -n 1)

.PHONY: help deps deps-dev bootstrap env-check models-volume up down restart logs ps urls \
	dev test prod dev-down test-down prod-down dev-local superadmin seed \
	check check-frontend check-backend check-integration check-db check-api check-e2e \
	check-stack-up check-stack-down

help:
	@echo "AMRAP — common commands"
	@echo ""
	@echo "Environments (settings in .env.dev / .env.test / .env.prod):"
	@echo "  make bootstrap ENV=dev|test|prod  - create .env.<env> from env/<env>.env.example"
	@echo "  make dev        - dev: hot reload (Vite + Spring Boot restart), host Postgres AMRAP-Dev"
	@echo "  make test       - test: production-like build on its own ports, host Postgres AMRAP-Test"
	@echo "  make prod       - prod: production build, only the web port published, host Postgres AMRAP-Prod"
	@echo "  make dev-down | test-down | prod-down  - stop an environment (data is kept)"
	@echo "  make logs ENV=x | ps ENV=x | restart ENV=x | urls ENV=x"
	@echo "  make superadmin ENV=x  - create the first superadmin from SUPERADMIN_* in .env.<env>"
	@echo "  make dev-local  - dev with the API on host Maven (no maven image needed)"
	@echo ""
	@echo "Automated test suite (separate disposable stack, never touches an environment):"
	@echo "  make check      - run every suite and print a summary (needs Docker, Java 21, Maven)"
	@echo "  make check-frontend | check-backend | check-integration | check-db | check-api | check-e2e"
	@echo "  make check-stack-up / check-stack-down - isolated stack used by check-api and check-e2e"
	@echo ""
	@echo "Setup: make deps (Docker) | make deps-dev (+ Java, Maven, Node)"

deps:
	@./scripts/install-deps.sh

deps-dev:
	@./scripts/install-deps.sh --with-dev

bootstrap:
	@./scripts/bootstrap.sh $(ENV)

# --- Environments ---

env-check:
	@case "$(ENV)" in dev|test|prod) ;; *) echo "ENV must be dev, test or prod (got '$(ENV)')" >&2; exit 2;; esac
	@test -f $(ENV_FILE) || { echo "$(ENV_FILE) not found. Create it with: make bootstrap ENV=$(ENV)" >&2; exit 2; }

# The Ollama model volume is shared by all environments, so it is created outside any compose project.
models-volume: env-check
	@vol="$(call env_value,OLLAMA_MODELS_VOLUME)"; \
	  test -n "$$vol" || { echo "OLLAMA_MODELS_VOLUME is not set in $(ENV_FILE)" >&2; exit 2; }; \
	  docker volume inspect "$$vol" >/dev/null 2>&1 || docker volume create "$$vol" >/dev/null

up: models-volume
	@echo "==> [$(ENV)] starting project $(call env_value,COMPOSE_PROJECT_NAME)"
	$(COMPOSE) up -d --build
	@$(MAKE) --no-print-directory urls ENV=$(ENV)

down: env-check
	@echo "==> [$(ENV)] stopping project $(call env_value,COMPOSE_PROJECT_NAME)"
	$(COMPOSE) down

restart: env-check
	$(COMPOSE) restart

logs: env-check
	$(COMPOSE) logs -f

ps: env-check
	$(COMPOSE) ps

urls: env-check
	@echo "==> [$(ENV)] web: http://localhost:$(call env_value,WEB_PORT)  (API via /api on the same address)"

dev:
	@$(MAKE) --no-print-directory up ENV=dev

test:
	@$(MAKE) --no-print-directory up ENV=test

prod:
	@$(MAKE) --no-print-directory up ENV=prod

dev-down:
	@$(MAKE) --no-print-directory down ENV=dev

test-down:
	@$(MAKE) --no-print-directory down ENV=test

prod-down:
	@$(MAKE) --no-print-directory down ENV=prod

# Hot reload with the API on host Java/Maven (avoids pulling the maven image).
# Requires once: sudo apt-get install -y maven inotify-tools
dev-local:
	@$(MAKE) --no-print-directory models-volume ENV=dev
	@./scripts/dev-hotreload.sh

superadmin: env-check
	@./scripts/create-superadmin.sh $(ENV)

seed:
	@./scripts/seed-demo-data.sh

# --- Automated test suite (see README "Testing") ---

check:
	@./test/run-all.sh

check-frontend:
	@./test/run-all.sh frontend

check-backend:
	@./test/run-all.sh backend

check-integration:
	@./test/run-all.sh integration

check-db:
	@./test/run-all.sh database

check-api:
	@./test/run-all.sh api

check-e2e:
	@./test/run-all.sh e2e

check-stack-up:
	@./test/stack/stack.sh up

check-stack-down:
	@./test/stack/stack.sh down
