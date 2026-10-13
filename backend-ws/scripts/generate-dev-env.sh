#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ENV_FILE="${ROOT_DIR}/.env"
ENV_EXAMPLE="${ROOT_DIR}/.env.example"

CERT_DIR="${ROOT_DIR}/local-env"
CLIENT_CERT="${CERT_DIR}/client.crt"

STORE_PASSWORD="${STORE_PASSWORD:-changeit}"
CLIENT_PASSWORD="${CLIENT_PASSWORD:-changeit}"

# ==========================================
# CREAR .env
# ==========================================

if [ ! -f "${ENV_FILE}" ]; then
    cp "${ENV_EXAMPLE}" "${ENV_FILE}"
    echo "✅ .env creado desde .env.example"
fi


# ==========================================
# UTILIDAD PARA ACTUALIZAR .env
# ==========================================

set_env() {
    local key="$1"
    local value="$2"

    if grep -q "^${key}=" "${ENV_FILE}"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "${ENV_FILE}"
    else
        printf '%s=%s\n' "${key}" "${value}" >> "${ENV_FILE}"
    fi
}


# ==========================================
# AUTH TOKEN
# ==========================================

CURRENT_TOKEN="$(
    grep '^AUTH_TOKEN=' "${ENV_FILE}" |
    cut -d= -f2-
)" || true

if [ -z "${CURRENT_TOKEN}" ]; then
    AUTH_TOKEN="$(openssl rand -hex 32)"

    set_env "AUTH_TOKEN" "${AUTH_TOKEN}"

    echo "🔑 AUTH_TOKEN generado."
else
    echo "ℹ️ AUTH_TOKEN ya existente."
fi


# ==========================================
# FINGERPRINT CLIENTE
# ==========================================

FINGERPRINT="$(
     openssl x509 \
         -in "${CLIENT_CERT}" \
         -noout \
         -fingerprint \
         -sha256 |
    cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'
 )"

# ==========================================
# CONFIGURACIÓN SSL
# ==========================================

set_env \
    "JETTY_SSL_KEYSTORE_PATH" \
    "local-env/keystore.p12"

set_env \
    "JETTY_SSL_KEYSTORE_PASSWORD" \
    "${STORE_PASSWORD}"

set_env \
    "JETTY_SSL_TRUSTSTORE_PATH" \
    "local-env/truststore.p12"

set_env \
    "JETTY_SSL_TRUSTSTORE_PASSWORD" \
    "${STORE_PASSWORD}"

set_env \
    "JETTY_SSL_REQUIRECLIENTAUTH" \
    "true"

set_env \
    "AUTH_CERT_WHITELIST" \
    "${FINGERPRINT}"

set_env \
    "GRPC_TLS_ENABLED" \
    "false"

echo "✅ .env de Backend WS preparado."