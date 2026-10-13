# =============================================================================
# SensIA - monorepo
# Targets comunes: help, setup, run, test, clean, clean-all
# Por proyecto:    <setup|run|test|clean|clean-all>-<ner|ws|back|front>
# =============================================================================
.DEFAULT_GOAL := help

PROYECTOS := ner ws back front
DIR_ner   := backend-ner
DIR_ws    := backend-ws
DIR_back  := backend-app
DIR_front := frontend-app
DIR_docs  := docs

OBS_COMPOSE = GRAFANA_PASSWORD="$${GRAFANA_PASSWORD:-admin}" docker compose -p sensia-obs-dev \
	-f observability/docker-compose.yml -f observability/docker-compose.dev.yml --profile obs

POR_PROYECTO := $(foreach t,setup run test clean clean-all,$(addprefix $(t)-,$(PROYECTOS))) \
                clean-docs clean-all-docs
SERVICIOS    := $(addprefix dev-services-,up down logs clean)

.PHONY: help setup run run-no-ws test clean clean-all build-docs dev-obs-up dev-obs-down version \
         $(POR_PROYECTO) $(SERVICIOS)

VERSION := $(shell cat VERSION 2>/dev/null)

help: ## Muestra la ayuda
	@echo "SensIA:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""
	@echo "  Por proyecto: make <setup|run|test|clean|clean-all>-<ner|ws|back|front>"
	@echo "  Servicios:    make dev-services-<up|down|logs|clean>  (Keycloak y smtp4dev)"
	@echo "  Detalle:      make -C <directorio> help"

# común

setup: $(addprefix setup-,$(PROYECTOS)) ## Prepara todos los proyectos

run: ## Arranca todo el entorno de desarrollo
	$(MAKE) -j4 $(addprefix run-,$(PROYECTOS))

test: ## Ejecuta todos los tests
	$(MAKE) -j4 $(addprefix test-,$(PROYECTOS))

clean: $(addprefix clean-,$(PROYECTOS)) ## Elimina los artefactos generados (no toca docs)

# NER solo con clean porque modelo ONNX lento de regenerar
clean-all: clean-ner $(addprefix clean-all-,ws back front docs) ## Limpia por completo los entornos locales

run-no-ws: ## Arranca todo salvo backend-ws
	$(MAKE) -j3 $(addprefix run-,ner back front)

build-docs: ## Genera los PDF de los manuales
	$(MAKE) -C $(DIR_docs) build


# Por proyecto

$(addprefix setup-,$(PROYECTOS)): setup-%:
	$(MAKE) -C $(DIR_$*) setup

$(addprefix run-,ner ws front): run-%:
	$(MAKE) -C $(DIR_$*) run

run-back:
	$(MAKE) -C $(DIR_back) dev

$(addprefix test-,$(PROYECTOS)): test-%:
	$(MAKE) -C $(DIR_$*) test

$(addprefix clean-,$(PROYECTOS) docs): clean-%:
	$(MAKE) -C $(DIR_$*) clean

$(addprefix clean-all-,$(PROYECTOS) docs): clean-all-%:
	$(MAKE) -C $(DIR_$*) clean-all


# Entorno de desarrollo

$(SERVICIOS):
	$(MAKE) -C $(DIR_back) $@

dev-obs-up: ## Levanta la observabilidad local (Grafana, Loki, Tempo)
	mkdir -p backend-app/logs backend-ws/logs backend-ner/logs
	$(OBS_COMPOSE) up -d

dev-obs-down: ## Detiene la observabilidad local
	$(OBS_COMPOSE) down

version: ## Copia VERSION en el pom y los package.json, y comprueba el CHANGELOG
	@test -n "$(VERSION)" || { echo "❌ Falta el fichero VERSION"; exit 1; }
	sed -i -E '0,/<version>[^<]+<\/version>/s//<version>$(VERSION)<\/version>/' $(DIR_ws)/pom.xml
	cd $(DIR_front) && npm version $(VERSION) --no-git-tag-version --allow-same-version >/dev/null
	cd $(DIR_back)  && npm version $(VERSION) --no-git-tag-version --allow-same-version >/dev/null
	cd $(DIR_docs)  && npm version $(VERSION) --no-git-tag-version --allow-same-version >/dev/null
	@grep -q '^## \[$(VERSION)\] - [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}' CHANGELOG.md \
	  || echo "⚠️  CHANGELOG.md: falta '## [$(VERSION)] - AAAA-MM-DD' con la fecha real"
	@echo "✅ Versión $(VERSION) aplicada"