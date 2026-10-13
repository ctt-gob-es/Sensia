# ETAPA 1: Generar ONNX

FROM python:3.12-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements-build.txt .
# PyTorch solo CPU
RUN pip install --default-timeout=1000 --no-cache-dir \
    --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-build.txt

COPY scripts/ scripts/

RUN set -a && . scripts/.env.build && set +a && \
    HF_HUB_OFFLINE=0 TRANSFORMERS_OFFLINE=0 python scripts/export_onnx.py


# ETAPA 2: microservicio NER

FROM python:3.12-slim AS runner

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=.:src/protos \
    HF_HOME=/tmp/huggingface

WORKDIR /app

COPY requirements.txt .
RUN pip install --default-timeout=1000 --no-cache-dir -r requirements.txt

COPY src/ src/

# El código gRPC no se versiona, se genera aquí
RUN python -m grpc_tools.protoc -I=src/protos \
      --python_out=src/protos --grpc_python_out=src/protos src/protos/ner.proto

# Modelo ONNX y tokenizador
COPY --from=builder /app/models/ ./models/

# Sin root, lee los certificados con el grupo del compose
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin sensia
USER 10001

EXPOSE 50051

# Python es el PID 1 y recibe SIGTERM
CMD ["python", "-m", "src.main"]
