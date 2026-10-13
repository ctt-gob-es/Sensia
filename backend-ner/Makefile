# =============================================================================
# Backend NER
# Targets comunes del monorepo: help, setup, run, test, clean, clean-all
# =============================================================================
.DEFAULT_GOAL := help

VENV        := venv
BUILD_VENV  := venv-build
PYTHON      := $(VENV)/bin/python
PIP         := $(VENV)/bin/pip
BUILD_PIP   := $(BUILD_VENV)/bin/pip

ENV_FILE    ?= .env
BUILD_ENV_FILE ?= scripts/.env.build
IMAGEN      ?= sensia-backend-ner:latest

# PyTorch: solo CPU para la exportación para evitar descargar CUDA
TORCH_INDEX ?= https://download.pytorch.org/whl/cpu

ONNX_MODEL  := models/roberta_bsc.onnx
PROTOS      := src/protos/ner_pb2.py
CERTS_DIR   := certs

# Rutas de importación: el paquete src y el código gRPC generado
export PYTHONPATH := .:src/protos

CARGAR_ENV = set -a; . "$(abspath $(ENV_FILE))"; set +a;
CARGAR_ENV_BUILD = set -a; . "$(abspath $(BUILD_ENV_FILE))"; set +a;

PROTOC = $(PYTHON) -m grpc_tools.protoc -I=src/protos \
	--python_out=src/protos --grpc_python_out=src/protos src/protos/ner.proto

define CREAR_VENV
@echo "Creando el entorno virtual ($$(python3 --version))..."
rm -rf $(VENV)
python3 -m venv $(VENV)
$(PIP) install --upgrade pip
$(PIP) install -r requirements-dev.txt
endef

define EXPORTAR_ONNX
@echo "Creando el entorno de exportación ONNX..."
rm -rf $(BUILD_VENV)
python3 -m venv $(BUILD_VENV)
$(BUILD_PIP) install --upgrade pip
$(BUILD_PIP) install --extra-index-url $(TORCH_INDEX) -r requirements-build.txt
@echo "Descargando el modelo y exportándolo a ONNX..."
@$(CARGAR_ENV_BUILD) HF_HUB_OFFLINE=0 TRANSFORMERS_OFFLINE=0 $(BUILD_VENV)/bin/python scripts/export_onnx.py
rm -rf $(BUILD_VENV)
endef

.PHONY: help setup run test clean clean-all \
        compile test-cov \
        dev-env dev-certs dev-model dev-env-clean dev-certs-clean dev-model-clean \
        docker-build docker-run docker-clean

help: ## Muestra esta ayuda
	@echo "🧠 Backend NER:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z-]+:.*## / {printf "  make %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# Común

setup: ## Recrea el entorno virtual y compila los protos
	$(CREAR_VENV)
	$(PROTOC)

run: $(ENV_FILE) $(VENV) $(ONNX_MODEL) $(PROTOS) $(CERTS_DIR) ## Arranca el servicio
	@echo "Arrancando el microservicio (modo desarrollo)..."
	@$(CARGAR_ENV) $(PYTHON) -m src.main

test: $(ENV_FILE) $(VENV) $(PROTOS) ## Ejecuta los tests
	@echo "🧪 Ejecutando los tests con Pytest..."
	@$(CARGAR_ENV) $(VENV)/bin/pytest tests/ -v -s -W ignore::DeprecationWarning

clean: ## Elimina las cachés de Python (__pycache__)
	@echo "Eliminando entorno y artefactos de desarrollo..."
	find . -type d -name "__pycache__" -prune -exec rm -rf {} +

clean-all: clean dev-model-clean dev-certs-clean dev-env-clean ## Limpieza total: entorno, modelo, certificados y .env
	@echo "✅ Backend NER completamente limpio."

# Compilación y tests

compile: $(VENV) ## Compila los Protocol Buffers de gRPC
	$(PROTOC)

test-cov: $(ENV_FILE) $(VENV) $(PROTOS) ## Ejecuta los tests con cobertura
	@echo "📊 Ejecutando los tests con cobertura..."
	$(PIP) install -q pytest-cov
	@$(CARGAR_ENV) $(VENV)/bin/pytest tests/ --cov=src --cov-report=term-missing

# Entorno de desarrollo

dev-env: $(ENV_FILE) ## Crea el .env desde .env.example o lo regenera si este cambia

dev-certs: ## Genera (o regenera) los certificados mTLS de desarrollo
	bash ./scripts/gen_certs.sh

dev-model: | $(BUILD_ENV_FILE) ## Descarga el modelo y lo exporta a ONNX
	$(EXPORTAR_ONNX)

dev-env-clean: ## Elimina el .env
	rm -f $(ENV_FILE) $(ENV_FILE).bak

dev-certs-clean: ## Elimina los certificados de desarrollo
	rm -rf $(CERTS_DIR)

dev-model-clean: ## Elimina el modelo exportado
	rm -rf $(BUILD_VENV) models/*.onnx models/*.onnx.data models/tokenizer models/origen_modelo.json models

# Dependencias

deps-audit: $(VENV) ## Audita vulnerabilidades de las dependencias Python
	$(PIP) install -q pip-audit
	$(VENV)/bin/pip-audit -r requirements.txt

deps-update: $(VENV) ## Busca actualizaciones de las dependencias Python
	$(PIP) install -q pip-upgrader
	$(VENV)/bin/pip-upgrade requirements.txt

# Docker

docker-build: ## Construye la imagen
	docker build -t $(IMAGEN) .

# Con el .env de desarrollo (ENV=development: sin mTLS)
docker-run: ## Ejecuta la imagen con el .env de desarrollo
	docker run --rm --env-file $(ENV_FILE) -p 127.0.0.1:50051:50051 --name ner-service $(IMAGEN)

docker-clean: ## Elimina la imagen
	docker rmi $(IMAGEN)

# Ficheros generados

$(ENV_FILE): .env.example
	@[ ! -f $@ ] || { cp $@ $@.bak; echo "♻️  .env.example ha cambiado: se regenera $@ (anterior en $@.bak)"; }
	cp .env.example $@

$(BUILD_ENV_FILE):
	@echo "❌ Falta $@: es necesario para exportar el modelo ONNX."; exit 1	

$(VENV): requirements.txt requirements-dev.txt
	$(CREAR_VENV)
	@touch $@

$(ONNX_MODEL): scripts/export_onnx.py requirements-build.txt $(BUILD_ENV_FILE)
	$(EXPORTAR_ONNX)

$(PROTOS): src/protos/ner.proto | $(VENV)
	$(PROTOC)

$(CERTS_DIR):
	bash ./scripts/gen_certs.sh
