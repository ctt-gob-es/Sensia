# =============================================================================
# Frontend App
# Targets comunes del monorepo: help, setup, run, test, clean, clean-all
# =============================================================================
.DEFAULT_GOAL := help

NPM_INSTALL = if [ -f package-lock.json ]; then npm ci --include=dev; else npm install --include=dev; fi

.PHONY: help setup run test clean clean-all

help: ## Muestra esta ayuda
	@echo "🅰️ Frontend App:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ---------- Contrato común ----------

setup: ## Instala las dependencias
	@$(NPM_INSTALL)

run: | node_modules ## Instala lo que falte y arranca Angular
	npm run start

test: | node_modules ## Ejecuta los tests una vez (sin modo watch)
	@echo "ℹ️ Frontend App no tiene tests."

clean: ## Elimina dependencias y artefactos generados
	rm -rf dist coverage .angular out-tsc node_modules

clean-all: clean ## Limpieza total (no hay más entorno local)

# ---------- Ficheros generados ----------

node_modules:
	@$(NPM_INSTALL)