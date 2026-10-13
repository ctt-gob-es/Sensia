#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="${ROOT_DIR}/local-env"

STORE_PASSWORD="${STORE_PASSWORD:-changeit}"
CLIENT_PASSWORD="${CLIENT_PASSWORD:-changeit}"

CA_KEY="${CERT_DIR}/ca.key"
CA_CERT="${CERT_DIR}/ca.crt"

SERVER_KEY="${CERT_DIR}/server.key"
SERVER_CSR="${CERT_DIR}/server.csr"
SERVER_CERT="${CERT_DIR}/server.crt"

CLIENT_KEY="${CERT_DIR}/client.key"
CLIENT_CSR="${CERT_DIR}/client.csr"
CLIENT_CERT="${CERT_DIR}/client.crt"

KEYSTORE="${CERT_DIR}/keystore.p12"
TRUSTSTORE="${CERT_DIR}/truststore.p12"
CLIENT_STORE="${CERT_DIR}/cliente_001.p12"

mkdir -p "${CERT_DIR}"


# CA DE DESARROLLO


if [ ! -f "${CA_KEY}" ] || [ ! -f "${CA_CERT}" ]; then
    echo " Generando CA local de SensIA..."

    openssl req \
        -x509 \
        -newkey rsa:4096 \
        -sha256 \
        -nodes \
        -days 3650 \
        -keyout "${CA_KEY}" \
        -out "${CA_CERT}" \
        -subj "/CN=SensIA Development CA"
else
    echo " CA local ya existente."
fi



# CERTIFICADO SERVIDOR


if [ ! -f "${SERVER_KEY}" ] || [ ! -f "${SERVER_CERT}" ]; then
    echo " Generando certificado de servidor..."

    openssl req \
        -new \
        -newkey rsa:2048 \
        -nodes \
        -keyout "${SERVER_KEY}" \
        -out "${SERVER_CSR}" \
        -subj "/CN=localhost"

    cat > "${CERT_DIR}/server.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EOF

    openssl x509 \
        -req \
        -sha256 \
        -days 825 \
        -in "${SERVER_CSR}" \
        -CA "${CA_CERT}" \
        -CAkey "${CA_KEY}" \
        -CAcreateserial \
        -out "${SERVER_CERT}" \
        -extfile "${CERT_DIR}/server.ext"
else
    echo " Certificado servidor ya existente."
fi



# CERTIFICADO CLIENTE


if [ ! -f "${CLIENT_KEY}" ] || [ ! -f "${CLIENT_CERT}" ]; then
    echo " Generando certificado cliente..."

    openssl req \
        -new \
        -newkey rsa:2048 \
        -nodes \
        -keyout "${CLIENT_KEY}" \
        -out "${CLIENT_CSR}" \
        -subj "/CN=sensia-dev-client"

    cat > "${CERT_DIR}/client.ext" <<EOF
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
EOF

    openssl x509 \
        -req \
        -sha256 \
        -days 825 \
        -in "${CLIENT_CSR}" \
        -CA "${CA_CERT}" \
        -CAkey "${CA_KEY}" \
        -CAcreateserial \
        -out "${CLIENT_CERT}" \
        -extfile "${CERT_DIR}/client.ext"
else
    echo " Certificado cliente ya existente."
fi



# KEYSTORE JETTY


echo " Generando keystore.p12..."

openssl pkcs12 \
    -export \
    -name sensia-server \
    -inkey "${SERVER_KEY}" \
    -in "${SERVER_CERT}" \
    -certfile "${CA_CERT}" \
    -out "${KEYSTORE}" \
    -passout "pass:${STORE_PASSWORD}"



# TRUSTSTORE JETTY


echo " Generando truststore.p12..."

rm -f "${TRUSTSTORE}"

keytool -importcert \
    -noprompt \
    -alias sensia-dev-ca \
    -file "${CA_CERT}" \
    -keystore "${TRUSTSTORE}" \
    -storetype PKCS12 \
    -storepass "${STORE_PASSWORD}"



# PKCS12 CLIENTE


echo " Generando cliente_001.p12..."

openssl pkcs12 \
    -export \
    -name sensia-dev-client \
    -inkey "${CLIENT_KEY}" \
    -in "${CLIENT_CERT}" \
    -certfile "${CA_CERT}" \
    -out "${CLIENT_STORE}" \
    -passout "pass:${CLIENT_PASSWORD}"



# FINGERPRINT CLIENTE


FINGERPRINT="$(
    openssl x509 \
        -in "${CLIENT_CERT}" \
        -noout \
        -fingerprint \
        -sha256 |
    cut -d= -f2
)"

echo ""
echo " Certificados de desarrollo generados."
echo "   Keystore:   ${KEYSTORE}"
echo "   Truststore: ${TRUSTSTORE}"
echo "   Cliente:    ${CLIENT_STORE}"
echo ""
echo " Fingerprint cliente:"
echo "${FINGERPRINT}"
