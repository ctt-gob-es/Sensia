# =============================================================================
# Backend WS
# Targets comunes del monorepo: help, setup, run, test, clean, clean-all
# =============================================================================
.DEFAULT_GOAL := help

ENV_FILE        := .env
CERTS_DIR       := local-env
STORE_PASSWORD  ?= changeit
CLIENT_PASSWORD ?= changeit

# Ajustar si OpenCV está instalado en otra ruta
OPENCV_LIBRARY_PATH ?= /usr/local/share/java/opencv4/

DEV_PASSWORDS = STORE_PASSWORD="$(STORE_PASSWORD)" CLIENT_PASSWORD="$(CLIENT_PASSWORD)"

# Carga el .env y la ruta nativa de OpenCV
CARGAR_ENV = set -a; . ./$(ENV_FILE); set +a; \
	export MAVEN_OPTS="-Djava.library.path=$(OPENCV_LIBRARY_PATH) $${MAVEN_OPTS:-}"; \
	if [ -d target/classes ] && grep -rlq "Unresolved compilation" target/classes; then \
		echo "♻️  Clases compiladas por el IDE con errores: mvn clean"; mvn -q clean; fi;

# Compila, arranca el servidor en segundo plano, espera a que responda y lanza los tests
TEST_CON_SERVIDOR = $(CARGAR_ENV) \
	echo "🚀 Levantando servidor..."; \
	mvn -q compile || exit 1; \
	mvn exec:java > /dev/null 2>&1 & PID=$$!; \
	trap 'echo "🛑 Apagando servidor..."; kill $$PID 2>/dev/null || true' EXIT INT; \
	n=0; until curl -s -m 2 -o /dev/null http://127.0.0.1:$${JETTY_HTTP_PORT:?falta JETTY_HTTP_PORT en .env}/; do \
		kill -0 $$PID 2>/dev/null || { echo "❌ El servidor se ha parado (make run para ver el error)"; exit 1; }; \
		n=$$((n+1)); [ $$n -lt 120 ] || { echo "❌ El servidor no responde tras 120 s"; exit 1; }; \
		sleep 1; \
	done; \
	mvn $(MVN_TEST_ARGS) test

.PHONY: help setup run test clean clean-all \
        test-one test-no-server test-clean \
        dev-env dev-certs dev-env-clean dev-certs-clean

help: ## Muestra esta ayuda
	@echo "☕ Backend WS:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# Contrato común

setup: dev-env ## Prepara el entorno y compila (sin tests)
	mvn clean install -DskipTests

run: dev-env ## Prepara el entorno y arranca el servicio
	@$(CARGAR_ENV) mvn compile exec:java

test: dev-env ## Ejecuta los tests con el servidor arrancado
	@$(TEST_CON_SERVIDOR)

clean: ## Elimina los artefactos de Maven
	mvn clean
	rm -f dependency-reduced-pom.xml

clean-all: clean test-clean dev-certs-clean dev-env-clean ## Limpieza total: artefactos, datos de test, certificados y .env


# Compilación y tests

test-one: MVN_TEST_ARGS = -Dtest="$(TEST)"
test-one: dev-env ## Ejecuta un test: make test-one TEST=RedactFacesTest
	@test -n "$(TEST)" || { echo "❌ Indica TEST=<nombre>, p. ej.: make test-one TEST=RedactFacesTest"; exit 1; }
	@$(TEST_CON_SERVIDOR)

test-no-server: dev-env ## Ejecuta los tests sin arrancar el servidor
	@$(CARGAR_ENV) mvn test

test-clean: ## Elimina los datos y resultados de los tests
	rm -rf test-data test-output

# Entorno de desarrollo

dev-certs: ## Genera los certificados locales
	@$(DEV_PASSWORDS) bash ./scripts/generate-dev-certs.sh

dev-env: dev-certs ## Genera el .env desde .env.example (lo regenera si este cambia)
	@if [ -f $(ENV_FILE) ] && [ $(ENV_FILE) -ot .env.example ]; then \
		mv $(ENV_FILE) $(ENV_FILE).bak; echo "♻️  .env.example ha cambiado: se regenera $(ENV_FILE) (anterior en $(ENV_FILE).bak)"; fi
	@$(DEV_PASSWORDS) bash ./scripts/generate-dev-env.sh

dev-env-clean: ## Elimina el .env y los logs locales
	rm -rf $(ENV_FILE) $(ENV_FILE).bak logs logs-sensibles

dev-certs-clean: ## Elimina los certificados locales
	rm -rf $(CERTS_DIR)