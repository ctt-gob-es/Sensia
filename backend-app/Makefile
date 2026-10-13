# =============================================================================
# Backend App
# Targets comunes del monorepo: help, setup, run, test, clean, clean-all
# =============================================================================
.DEFAULT_GOAL := help

ENV_FILE    ?= .env
ENV_EXAMPLE ?= .env.example
DEV_COMPOSE := docker compose -f docker-compose.dev.yml

CERTS_DIR    := dev/keycloak/certs
KEYCLOAK_CA  := $(CERTS_DIR)/keycloak.crt
KEYCLOAK_KEY := $(CERTS_DIR)/keycloak.key
KEYCLOAK_URL := https://localhost:18443/realms/sensia-dev

DEV_OAUTH_PASSWORD ?= sensia-dev-password

NPM_INSTALL = if [ -f package-lock.json ]; then npm ci --include=dev; else npm install --include=dev; fi
ARRANCAR    = NODE_EXTRA_CA_CERTS=$(KEYCLOAK_CA) npm run dev

.PHONY: help setup run test clean clean-all \
        dev dev-env dev-certs dev-env-clean dev-certs-clean \
        dev-services-up dev-services-down dev-services-logs dev-services-clean \
        dev-keycloak-wait dev-refresh-token \
        deps-audit deps-outdated deps-update

help: ## Muestra esta ayuda
	@echo "🟩 Backend App:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: dev-env ## Crea el .env si falta e instala las dependencias
	@$(NPM_INSTALL)

run: dev-env | node_modules ## Arranca solo Backend App
	$(ARRANCAR)

test: dev-env | node_modules ## Ejecuta los tests
	npm test

clean: ## Elimina dependencias, artefactos y logs
	rm -rf dist coverage .nyc_output logs node_modules

clean-all: clean ## Limpieza total: además, servicios con volúmenes, BD, certificados y .env
	@$(DEV_COMPOSE) down -v --remove-orphans 2>/dev/null || true
	rm -rf bdd $(CERTS_DIR) $(ENV_FILE) $(ENV_FILE).bak

dev: dev-refresh-token | node_modules ## Levanta Keycloak y smtp4dev, renueva el token OAuth2 y arranca
	$(ARRANCAR)

dev-env: $(ENV_FILE) ## Crea el .env desde .env.example o lo regenera si este cambia

dev-certs: ## Genera el certificado HTTPS de Keycloak si no existe
	@[ -f $(KEYCLOAK_CA) ] && [ -f $(KEYCLOAK_KEY) ] || { \
		$(DEV_COMPOSE) down 2>/dev/null || true; \
		mkdir -p $(CERTS_DIR) && \
		openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
			-keyout $(KEYCLOAK_KEY) -out $(KEYCLOAK_CA) \
			-subj "/CN=localhost" -addext "subjectAltName=DNS:localhost" && \
		chmod 644 $(KEYCLOAK_KEY); }

dev-env-clean: ## Elimina el .env
	rm -f $(ENV_FILE) $(ENV_FILE).bak

dev-certs-clean: ## Elimina el certificado de Keycloak
	rm -rf $(CERTS_DIR)

dev-services-up: dev-certs ## Arranca Keycloak y smtp4dev
	$(DEV_COMPOSE) up -d --build

dev-services-down: ## Detiene los servicios de desarrollo
	$(DEV_COMPOSE) down

dev-services-logs: ## Muestra los logs de los servicios de desarrollo
	$(DEV_COMPOSE) logs -f

dev-services-clean: ## Elimina los servicios de desarrollo y sus volúmenes
	$(DEV_COMPOSE) down -v

dev-keycloak-wait: dev-services-up
	@echo "⏳ Esperando a Keycloak..."
	@n=0; until curl -fsS --max-time 3 --cacert $(KEYCLOAK_CA) $(KEYCLOAK_URL)/.well-known/openid-configuration >/dev/null 2>&1; do \
		n=$$((n+1)); [ $$n -lt 120 ] || { echo "❌ Keycloak no responde (make dev-services-logs):"; \
			curl -fsS --max-time 3 --cacert $(KEYCLOAK_CA) $(KEYCLOAK_URL)/.well-known/openid-configuration >/dev/null; exit 1; }; \
		sleep 1; \
	done

dev-refresh-token: dev-env dev-keycloak-wait ## Pide un refresh token OAuth2 a Keycloak y lo guarda en el .env
	@set -a; . ./$(ENV_FILE); set +a; \
	set -- -d grant_type=password \
		-d "client_id=$$OAUTH_CLIENT_ID" -d "client_secret=$$OAUTH_CLIENT_SECRET" \
		-d "username=$$SMTP_USER" -d "password=$(DEV_OAUTH_PASSWORD)"; \
	[ -z "$${OAUTH_SCOPE:-}" ] || set -- "$$@" -d "scope=$$OAUTH_SCOPE"; \
	RESPONSE=$$(curl -sS --cacert $(KEYCLOAK_CA) "$$OAUTH_TOKEN_URL" "$$@"); \
	TOKEN=$$(printf '%s' "$$RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("refresh_token", ""))'); \
	[ -n "$$TOKEN" ] || { echo "❌ Keycloak no devolvió refresh_token:"; printf '%s\n' "$$RESPONSE"; exit 1; }; \
	if grep -q '^OAUTH_REFRESH_TOKEN=' $(ENV_FILE); then \
		sed -i "s|^OAUTH_REFRESH_TOKEN=.*|OAUTH_REFRESH_TOKEN=$$TOKEN|" $(ENV_FILE); \
	else \
		printf '\nOAUTH_REFRESH_TOKEN=%s\n' "$$TOKEN" >> $(ENV_FILE); \
	fi
	@echo "✅ Refresh token actualizado."

deps-audit: ## Audita vulnerabilidades de las dependencias
	npm audit

deps-outdated: ## Lista las dependencias desactualizadas
	npm outdated

deps-update: ## Actualiza package.json a las últimas versiones e instala
	npx npm-check-updates -u
	npm install --include=dev

$(ENV_FILE): $(ENV_EXAMPLE)
	@[ ! -f $@ ] || { cp $@ $@.bak; echo "♻️  $(ENV_EXAMPLE) ha cambiado: se regenera $@ (anterior en $@.bak)"; }
	cp $(ENV_EXAMPLE) $@

node_modules:
	@$(NPM_INSTALL)