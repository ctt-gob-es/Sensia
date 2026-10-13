# =============================================================================
# Docs
# Targets: help, build, clean, clean-all
# =============================================================================
.DEFAULT_GOAL := help

DOCS_COMPOSE := docker compose -f docker-compose.docs.yml

.PHONY: help build clean clean-all

help: ## Muestra esta ayuda
	@echo "📚 Docs:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ------------- común -------------

clean: ## Elimina dist/ con los PDF generados
	rm -rf dist/

clean-all: clean ## Además, elimina las imágenes y volúmenes de Docker de docs
	$(DOCS_COMPOSE) down --rmi all -v

# ---------- Documentación ----------

build: ## Genera los PDF de los manuales con Docker
	$(DOCS_COMPOSE) run --rm docs
	cp -f dist/*.pdf ./pdf
