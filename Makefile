SHELL := /bin/bash
COMPOSE := docker compose

.PHONY: help up down restart logs ps build pull psql shell-app env-check secrets

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

up: env-check ## Bring up the core stack
	$(COMPOSE) up -d --build

down: ## Stop and remove containers (volumes preserved)
	$(COMPOSE) down

restart: ## Restart all services
	$(COMPOSE) restart

logs: ## Tail logs
	$(COMPOSE) logs -f --tail=200

ps: ## Show service status
	$(COMPOSE) ps

build: ## Rebuild images
	$(COMPOSE) build

pull: ## Pull latest base images
	$(COMPOSE) pull

psql: ## psql into the app database as the superuser
	$(COMPOSE) exec postgres psql -U $$(grep ^PG_SUPERUSER= .env | cut -d= -f2) \
	                              -d $$(grep ^PG_DB_NAME= .env | cut -d= -f2)

shell-app: ## Shell into the app container
	$(COMPOSE) exec app sh

env-check: ## Sanity-check .env exists and placeholders are gone
	@test -f .env || (echo "ERROR: .env missing. cp .env.example .env and edit." && exit 1)
	@if [ "$$(stat -c '%a' .env 2>/dev/null || stat -f '%A' .env)" != "600" ]; then \
	  echo "WARNING: .env is not 0600. Run: chmod 600 .env"; \
	fi
	@grep -q CHANGEME .env && (echo "ERROR: .env still has CHANGEME placeholders." && exit 1) || true

secrets: ## Generate fresh random secrets to paste into .env
	@echo "PG_SUPERUSER_PASSWORD=$$(openssl rand -base64 32 | tr -d '=')"
	@echo "PG_APP_PASSWORD=$$(openssl rand -base64 32 | tr -d '=')"
	@echo "SYSOP_INITIAL_PASSWORD=$$(openssl rand -base64 24 | tr -d '=')"
