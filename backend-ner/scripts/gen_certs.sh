#!/usr/bin/env bash

set -euo pipefail

CERT_DIR="certs"
mkdir -p "$CERT_DIR"


echo "🔐 [1/4] Creando Autoridad Certificadora (CA) Privada..."
openssl req -x509 -nodes -newkey rsa:4096 -days 3650 \
  -keyout "$CERT_DIR/ca.key" \
  -out "$CERT_DIR/ca.crt" \
  -subj "/CN=RootCA-Microservicios/O=Enterprise/C=ES" \
  2>/dev/null


echo "🖥️  [2/4] Creando Certificado para el Servidor gRPC (NER)..."
openssl req -nodes -newkey rsa:2048 \
  -keyout "$CERT_DIR/server.key" \
  -out "$CERT_DIR/server.csr" \
  -subj "/CN=localhost/O=Enterprise/C=ES" \
  2>/dev/null


cat <<EOF > "$CERT_DIR/server.ext"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
IP.1 = 127.0.0.1
IP.2 = ::1
EOF


openssl x509 -req -in "$CERT_DIR/server.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -out "$CERT_DIR/server.crt" \
  -days 365 -extfile "$CERT_DIR/server.ext" \
  2>/dev/null


echo "🔑 [3/4] Creando Certificado para los Clientes Autorizados..."
openssl req -nodes -newkey rsa:2048 \
  -keyout "$CERT_DIR/client.key" \
  -out "$CERT_DIR/client.csr" \
  -subj "/CN=BackendClient/O=Enterprise/C=ES" \
  2>/dev/null


cat <<EOF > "$CERT_DIR/client.ext"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF


openssl x509 -req -in "$CERT_DIR/client.csr" \
  -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -out "$CERT_DIR/client.crt" \
  -days 365 -extfile "$CERT_DIR/client.ext" \
  2>/dev/null


echo "🧹 [4/4] Limpiando residuos de compilación criptográfica..."
rm "$CERT_DIR/server.csr" "$CERT_DIR/client.csr" "$CERT_DIR/server.ext" "$CERT_DIR/client.ext"
if [ -f "$CERT_DIR/ca.srl" ]; then rm "$CERT_DIR/ca.srl"; fi

chmod 600 "$CERT_DIR"/*.key
chmod 644 "$CERT_DIR"/*.crt


echo "✅ [ÉXITO] mTLS ha sido generado en la carpeta /$CERT_DIR/"
echo "-> ca.crt (Añadir a la confianza de clientes y servidores)"
echo "-> server.crt / server.key (Para este microservicio NER)"
echo "-> client.crt / client.key (Para el backend que haga las peticiones)"
