.PHONY: help up down logs ps bootstrap seed

help:
	@echo "AMRAP — common commands"
	@echo "  make bootstrap  - prepare local env from .env.example"
	@echo "  make up         - start all core services (db, api, web)"
	@echo "  make down       - stop and remove containers"
	@echo "  make logs       - tail compose logs"
	@echo "  make ps         - list running services"
	@echo "  make seed       - run demo data seed script"
	@echo "  make prod       - start with nginx (prod profile)"

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
