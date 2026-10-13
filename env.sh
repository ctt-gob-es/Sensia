#!/usr/bin/env bash
# =============================================================================
# SensIA - asistente de instalación
#
# Genera la PKI en ../pki y el fichero .env que usa docker compose.
# Se puede volver a ejecutar: reutiliza la CA interna, el certificado de cliente
# mTLS y los secretos de la instalación anterior, salvo que se pida regenerarlos.
#
# Uso: ./env.sh
# =============================================================================

set -Eeuo pipefail
umask 027                         # ficheros 640 y directorios 750 desde su creación

cd "$(dirname "$0")"
REPO_DIR="$(pwd)"

# PKI: un directorio por contenedor. ca/ y clientes/ no se montan nunca
PKI_DIR="$(cd .. && pwd)/pki"
DIR_CA="$PKI_DIR/ca"              # claves de las CA (700)
DIR_CLIENTES="$PKI_DIR/clientes"  # certificados de cliente para entregar (700)
DIR_PUBLICO="$PKI_DIR/publico"    # certificado del dominio / proxy-nginx
DIR_WS="$PKI_DIR/ws"              # keystore, truststore y cliente gRPC / backend-ws
DIR_NER="$PKI_DIR/ner"            # servidor gRPC / backend-ner
DIR_APP="$PKI_DIR/app"            # CA interna (pública) / backend-app
ENV_FILE="$REPO_DIR/.env"
EJEMPLO_FILE="$REPO_DIR/.env.example"
CA_INTERNA="ca_interna"           # servicios internos y certificado autofirmado del dominio
CA_CLIENTES="ca_clientes"         # certificados de cliente del SOAP (mTLS)
CLIENTE_MTLS="cliente_mtls"

# Textos para el resumen final
MODO_SSL=""; MTLS_TEXTO=""; CORREO_TEXTO=""; OBS_TEXTO=""

# Salida por pantalla
VERDE='\033[0;32m'; ROJO='\033[0;31m'; AMARILLO='\033[1;33m'; CIAN='\033[0;36m'; NC='\033[0m'

titulo() { echo -e "\n${CIAN}--- $* ---${NC}"; }
info()   { echo -e "${CIAN}[*] $*${NC}"; }
ok()     { echo -e "${VERDE}✅ $*${NC}"; }
aviso()  { echo -e "${AMARILLO}⚠️  $*${NC}"; }
error()  { echo -e "${ROJO}[!] $*${NC}" >&2; }
fallo()  { error "$*"; exit 1; }

trap 'error "Error inesperado en la línea $LINENO. El .env no se ha modificado."' ERR
trap 'echo; error "Cancelado por el usuario. El .env no se ha modificado."; exit 1' INT


# =============================================================================
# Variables del .env: docker-compose.yml y el compose de observabilidad. 
# Si el compose usa una variable nueva, HAY QUE INCLUIRLA:
# escribir_env y en .env.example.
# =============================================================================
VARIABLES_ENV=(
  DOMAIN_NAME CERT_FILE KEY_FILE PORT_PROXY_HTTP PORT_PROXY_HTTPS CERTS_GID
  SOAP_API_TOKEN KEYSTORE_PASSWORD TRUSTSTORE_PASSWORD
  REQUIRE_MTLS AUTH_CERT_WHITELIST PORT_WS_MTLS
  ENV LOG_LEVEL ENFORCE_SECURITY
  JWT_SECRET ALTCHA_HMAC_KEY DEFAULT_ADMIN_USER DEFAULT_ADMIN_PASSWORD HARD_MAINTENANCE
  SMTP_AUTH_METHOD SMTP_HOST SMTP_PORT SMTP_SECURE SMTP_USER SMTP_PASS EMAIL_FROM
  OAUTH_CLIENT_ID OAUTH_CLIENT_SECRET OAUTH_REFRESH_TOKEN OAUTH_TOKEN_URL OAUTH_SCOPE
  COMPOSE_PROFILES ENABLE_TRACING OTEL_SDK_DISABLED OTEL_TRACES_EXPORTER GRAFANA_PASSWORD
)


# Nunca se toman de .env.example: los secretos y valores sensibles los calcula el asistente
VARIABLES_NO_EJEMPLO=(
  CERTS_GID KEYSTORE_PASSWORD TRUSTSTORE_PASSWORD SOAP_API_TOKEN AUTH_CERT_WHITELIST
  JWT_SECRET ALTCHA_HMAC_KEY DEFAULT_ADMIN_PASSWORD GRAFANA_PASSWORD
  SMTP_PASS OAUTH_CLIENT_SECRET OAUTH_REFRESH_TOKEN
)

# fallback: si .env.example falta o no trae alguna variable
valores_por_defecto() {
  DOMAIN_NAME="sensia.tuorganismo.es"
  CERT_FILE=""; KEY_FILE=""
  PORT_PROXY_HTTP="80"; PORT_PROXY_HTTPS="443"
  CERTS_GID=""
  SOAP_API_TOKEN=""; KEYSTORE_PASSWORD=""; TRUSTSTORE_PASSWORD=""
  REQUIRE_MTLS="false"; AUTH_CERT_WHITELIST=""; PORT_WS_MTLS="8443"
  ENV="production"; LOG_LEVEL="INFO"; ENFORCE_SECURITY="true"
  JWT_SECRET=""; ALTCHA_HMAC_KEY=""; DEFAULT_ADMIN_USER=""; DEFAULT_ADMIN_PASSWORD=""
  HARD_MAINTENANCE="false"
  SMTP_AUTH_METHOD=""; SMTP_HOST="localhost"; SMTP_PORT="9999"; SMTP_SECURE="false"
  SMTP_USER=""; SMTP_PASS=""; EMAIL_FROM=""
  OAUTH_CLIENT_ID=""; OAUTH_CLIENT_SECRET=""; OAUTH_REFRESH_TOKEN=""; OAUTH_TOKEN_URL=""; OAUTH_SCOPE=""
  COMPOSE_PROFILES=""; ENABLE_TRACING="false"; OTEL_SDK_DISABLED="true"
  OTEL_TRACES_EXPORTER="none"; GRAFANA_PASSWORD=""
}


# Lee una variable de un fichero tipo .env (admite valores con o sin comillas)
leer_valor() {
  local fichero="$1" clave="$2" linea
  linea=$(grep -E "^${clave}=" "$fichero" | tail -n 1) || return 0
  linea="${linea#*=}"
  if [[ "$linea" == \'*\' || "$linea" == \"*\" ]]; then
    linea="${linea:1:${#linea}-2}"
  fi
  printf '%s' "$linea"
}


# Carga las variables del contrato desde un fichero tipo .env.
cargar_fichero() {
  local fichero="$1" modo="${2-}" clave valor
  for clave in "${VARIABLES_ENV[@]}"; do
    if [[ "$modo" == "sin_secretos" && " ${VARIABLES_NO_EJEMPLO[*]} " == *" $clave "* ]]; then
      continue
    fi
    valor=$(leer_valor "$fichero" "$clave")
    if [[ -n "$valor" ]]; then
      printf -v "$clave" '%s' "$valor"
    fi
  done
}


# Pregunta un dato. Enter acepta el valor por defecto.
preguntar() {
  local mensaje="$1" destino="$2" defecto="${3-}" respuesta
  while true; do
    if [[ -n "$defecto" ]]; then
      read -r -p "🔹 $mensaje [$defecto]: " respuesta
      respuesta="${respuesta:-$defecto}"
    else
      read -r -p "🔹 $mensaje: " respuesta
    fi
    if [[ "$respuesta" == *"'"* ]]; then
      error "No se admiten comillas simples."
      continue
    fi
    printf -v "$destino" '%s' "$respuesta"
    return 0
  done
}



preguntar_obligatorio() {
  local mensaje="$1" destino="$2" defecto="${3-}"
  while true; do
    preguntar "$mensaje" "$destino" "$defecto"
    [[ -n "${!destino}" ]] && return 0
    error "Este dato es obligatorio."
  done
}



preguntar_puerto() {
  local mensaje="$1" destino="$2"
  while true; do
    preguntar_obligatorio "$mensaje" "$destino" "${!destino}"
    if [[ "${!destino}" =~ ^[0-9]+$ ]] && (( ${!destino} >= 1 && ${!destino} <= 65535 )); then
      return 0
    fi
    error "Puerto no válido."
  done
}



# Pregunta un secreto sin mostrarlo. Si ya tiene valor, Enter lo conserva.
preguntar_secreto() {
  local mensaje="$1" destino="$2" respuesta
  local actual="${!destino-}"
  while true; do
    if [[ -n "$actual" ]]; then
      read -r -s -p "🔹 $mensaje [Enter para mantener el actual]: " respuesta
    else
      read -r -s -p "🔹 $mensaje: " respuesta
    fi
    echo
    respuesta="${respuesta:-$actual}"
    if [[ -z "$respuesta" ]]; then error "Este dato es obligatorio."; continue; fi
    if [[ "$respuesta" == *"'"* ]]; then error "No se admiten comillas simples."; continue; fi
    printf -v "$destino" '%s' "$respuesta"
    return 0
  done
}



preguntar_fichero() {
  local mensaje="$1" destino="$2" defecto="${3-}"
  while true; do
    preguntar "$mensaje" "$destino" "$defecto"
    [[ -f "${!destino}" ]] && return 0
    error "No existe el fichero '${!destino}'."
  done
}



# Devuelve 0 si la respuesta es sí. El segundo parámetro es el valor por defecto (s/n).
confirmar() {
  local mensaje="$1" defecto="${2:-n}" respuesta opciones="s/N"
  [[ "$defecto" == "s" ]] && opciones="S/n"
  read -r -p "🔹 $mensaje ($opciones): " respuesta
  respuesta="${respuesta:-$defecto}"
  [[ "$respuesta" =~ ^[sS]$ ]]
}



# Utilidades criptográficas
generar_hex()      { openssl rand -hex "$1"; }
generar_password() { openssl rand -base64 24 | tr -d '/+=\n'; }

# Ejecuta openssl y solo muestra su salida si falla
ossl() {
  local salida
  if ! salida=$(openssl "$@" 2>&1); then
    error "openssl $1 ha fallado:"
    echo "$salida" >&2
    exit 1
  fi
}



copiar_si_distinto() {
  if [[ "$(realpath "$1")" != "$(realpath -m "$2")" ]]; then
    cp "$1" "$2"
  fi
}



# Comprueba un certificado aportado: formato, pareja con la clave, caducidad, nombre y cadena
validar_certificado() {
  local cert="$1" key="$2" pub_cert pub_key comprobacion
  openssl x509 -noout -in "$cert" 2>/dev/null || fallo "'$cert' no es un certificado PEM válido."
  pub_cert=$(openssl x509 -noout -pubkey -in "$cert" | openssl sha256)
  pub_key=$(openssl pkey -pubout -in "$key" 2>/dev/null | openssl sha256) \
    || fallo "No se puede leer la clave '$key' (¿está protegida con contraseña?)."
  [[ "$pub_cert" == "$pub_key" ]] || fallo "La clave privada no corresponde al certificado."
  if ! openssl x509 -noout -checkend 2592000 -in "$cert" >/dev/null; then
    aviso "El certificado caduca en menos de 30 días o ya ha caducado."
  fi
  comprobacion=$(openssl x509 -noout -checkhost "$DOMAIN_NAME" -in "$cert")
  if [[ "$comprobacion" != *"does match"* ]]; then
    aviso "El certificado no incluye ${DOMAIN_NAME} entre sus nombres."
  fi
  if [[ $(grep -c "BEGIN CERTIFICATE" "$cert") -lt 2 ]]; then
    aviso "El fichero solo contiene un certificado. Si lo emite una CA intermedia, añade los intermedios al final."
  fi
}




# =============================================================================
# Pasos del asistente
# =============================================================================


comprobar_dependencias() {
  info "Comprobando dependencias..."
  local cmd falta=0
  for cmd in openssl docker grep sed cut tr stat realpath mktemp; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      error "Falta el comando '$cmd'."
      falta=1
    fi
  done
  if ! docker compose version >/dev/null 2>&1; then
    error "Falta Docker Compose v2 ('docker compose')."
    falta=1
  fi
  if ! docker info >/dev/null 2>&1; then
    error "No hay acceso a Docker. ¿Has cerrado sesión tras añadirte al grupo docker?"
    falta=1
  fi
  [[ "$falta" -eq 0 ]] || fallo "Instala lo que falta y vuelve a ejecutar el asistente."
  ok "Dependencias correctas."
}



paso_dominio() {
  titulo "1. Dominio y puertos"
  while true; do
    preguntar_obligatorio "Dominio de la aplicación" DOMAIN_NAME "$DOMAIN_NAME"
    [[ "$DOMAIN_NAME" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] && break
    error "Dominio no válido."
  done
  preguntar_puerto "Puerto HTTP publicado" PORT_PROXY_HTTP
  preguntar_puerto "Puerto HTTPS publicado" PORT_PROXY_HTTPS
}



preparar_directorios() {
  mkdir -p "$DIR_PUBLICO" "$DIR_WS" "$DIR_NER" "$DIR_APP" "$DIR_CA" "$DIR_CLIENTES"
  chmod 750 "$PKI_DIR" "$DIR_PUBLICO" "$DIR_WS" "$DIR_NER" "$DIR_APP"
  chmod 700 "$DIR_CA" "$DIR_CLIENTES"
}



# Genera clave y certificado firmados por una CA
# Uso: firmar_con_ca <ca> <destino sin extensión> <sujeto> <días> <extensiones>
firmar_con_ca() {
  local ca="$1" destino="$2" sujeto="$3" dias="$4" extensiones="$5" csr ext
  csr=$(mktemp); ext=$(mktemp)
  ossl genrsa -out "$destino.key" 2048
  ossl req -new -key "$destino.key" -subj "$sujeto" -out "$csr"
  printf '%s\n' "$extensiones" > "$ext"
  ossl x509 -req -in "$csr" -CA "$DIR_CA/$ca.crt" -CAkey "$DIR_CA/$ca.key" -CAcreateserial \
    -days "$dias" -sha256 -extfile "$ext" -out "$destino.crt"
  rm -f "$csr" "$ext"
}


# Crea una CA si no existe, con extensiones explícitas (no depende del openssl.cnf del sistema)
crear_ca() {
  local nombre="$1" cn="$2" cfg
  if [[ -f "$DIR_CA/$nombre.key" && -f "$DIR_CA/$nombre.crt" ]]; then
    ok "Se reutiliza la CA $nombre."
    return 0
  fi
  info "Generando la CA $nombre..."
  cfg=$(mktemp)
  printf '%s\n' '[req]' 'distinguished_name=dn' '[dn]' '[ext]' \
    'basicConstraints=critical,CA:TRUE,pathlen:0' \
    'keyUsage=critical,keyCertSign,cRLSign' \
    'subjectKeyIdentifier=hash' > "$cfg"
  ossl genrsa -out "$DIR_CA/$nombre.key" 4096
  ossl req -x509 -new -key "$DIR_CA/$nombre.key" -sha256 -days 3650 \
    -subj "/O=SensIA/CN=$cn" -config "$cfg" -extensions ext -out "$DIR_CA/$nombre.crt"
  rm -f "$cfg"
}



paso_ca() {
  titulo "2. Autoridades de certificación (en $DIR_CA; no se montan en ningún contenedor)"
  crear_ca "$CA_INTERNA"  "SensIA CA interna"
  crear_ca "$CA_CLIENTES" "SensIA CA de clientes"
  # De ca/ solo sale el certificado público de la CA interna
  local d
  for d in "$DIR_WS" "$DIR_NER" "$DIR_APP"; do
    cp "$DIR_CA/$CA_INTERNA.crt" "$d/ca.crt"
  done
}



ssl_autofirmado() {
  info "Generando certificado para ${DOMAIN_NAME} firmado por la CA interna..."
  firmar_con_ca "$CA_INTERNA" "$DIR_PUBLICO/$DOMAIN_NAME" "/O=SensIA/CN=${DOMAIN_NAME}" 825 \
"basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:${DOMAIN_NAME}"
  CERT_FILE="${DOMAIN_NAME}.crt"
  KEY_FILE="${DOMAIN_NAME}.key"
  MODO_SSL="Autofirmado (CA interna)"
  ok "Certificado generado."
}



ssl_organismo() {
  local origen_cert origen_key
  echo "Indica la ruta de los ficheros en formato PEM. El certificado debe incluir la cadena intermedia."
  preguntar_fichero "Certificado" origen_cert "$DIR_PUBLICO/${DOMAIN_NAME}.crt"
  preguntar_fichero "Clave privada (sin contraseña)" origen_key "$DIR_PUBLICO/${DOMAIN_NAME}.key"
  validar_certificado "$origen_cert" "$origen_key"
  CERT_FILE="$(basename "$origen_cert")"
  KEY_FILE="$(basename "$origen_key")"
  # fullchain.pem identifica la instalación con Let's Encrypt (certbot.sh renovar)
  if [[ "$CERT_FILE" == "fullchain.pem" || "$KEY_FILE" == "privkey.pem" ]]; then
    CERT_FILE="${DOMAIN_NAME}.crt"; KEY_FILE="${DOMAIN_NAME}.key"
  fi
  copiar_si_distinto "$origen_cert" "$DIR_PUBLICO/$CERT_FILE"
  copiar_si_distinto "$origen_key" "$DIR_PUBLICO/$KEY_FILE"
  MODO_SSL="Certificado del organismo"
  ok "Certificado del organismo configurado."
}



ssl_letsencrypt() {
  [[ "$PORT_PROXY_HTTP" == "80" ]] || aviso "Let's Encrypt valida por el puerto 80; con HTTP en $PORT_PROXY_HTTP \
                                            la renovación por webroot fallará si no tiene implementado un sistema de redireccion externo."
  [[ -x "$REPO_DIR/certbot.sh" ]] || fallo "No se encuentra certbot.sh (o no es ejecutable) en el repositorio."
  aviso "El dominio debe resolver a este servidor y el puerto 80 debe ser accesible desde internet."
  local le_email staging=""
  preguntar_obligatorio "Correo de contacto para Let's Encrypt" le_email "admin@${DOMAIN_NAME}"
  if confirmar "¿Usar el entorno de pruebas (staging)? Emite un certificado que los navegadores no aceptan" n; then
    staging="--staging"
  fi
  info "Solicitando certificado a Let's Encrypt..."
  # shellcheck disable=SC2086
  SALIDA="$DIR_PUBLICO" PROXY="proxy-nginx" "$REPO_DIR/certbot.sh" "$DOMAIN_NAME" "$le_email" $staging
  CERT_FILE="fullchain.pem"
  KEY_FILE="privkey.pem"
  MODO_SSL="Let's Encrypt${staging:+ (staging)}"
  ok "Certificado de Let's Encrypt obtenido."
}



paso_ssl() {
  titulo "3. Certificado HTTPS del dominio"
  echo "  1) Autofirmado con la CA interna (pruebas o intranet, hay que importar ca_interna.crt en los equipos)"
  echo "  2) Certificado del organismo (ficheros propios)"
  echo "  3) Let's Encrypt (requiere DNS público y puerto 80 accesible desde internet)"
  local opcion defecto="1"
  # Al reconfigurar se propone el modo actual, para no sustituir por error un certificado real
  if [[ "$CERT_FILE" == "fullchain.pem" ]]; then
    defecto="3"
  elif [[ -n "$CERT_FILE" && -f "$DIR_PUBLICO/$CERT_FILE" ]] \
       && ! openssl verify -no_check_time -CAfile "$DIR_CA/$CA_INTERNA.crt" "$DIR_PUBLICO/$CERT_FILE" >/dev/null 2>&1; then
    defecto="2"
  fi
  while true; do
    preguntar "Elige una opción" opcion "$defecto"
    case "$opcion" in
      1) ssl_autofirmado; return 0 ;;
      2) ssl_organismo;   return 0 ;;
      3) ssl_letsencrypt; return 0 ;;
      *) error "Opción no válida." ;;
    esac
  done
}



paso_certificados_internos() {
  titulo "4. Certificados internos (gRPC entre backend-ws y backend-ner)"
  
  firmar_con_ca "$CA_INTERNA" "$DIR_NER/ner_server" "/CN=backend-ner/O=Enterprise/C=ES" 825 \
"basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:backend-ner,DNS:localhost,IP:127.0.0.1"
  
  firmar_con_ca "$CA_INTERNA" "$DIR_WS/ws_client" "/CN=backend-ws-client/O=Enterprise/C=ES" 825 \
"basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth"
  ok "Certificados ner_server y ws_client generados."
}



paso_mtls() {
  titulo "5. SOAP con certificado de cliente (mTLS)"
  echo "Con mTLS, el SOAP se atiende solo en un puerto propio y exige certificado de cliente."
  local defecto="n"
  [[ "$REQUIRE_MTLS" == "true" ]] && defecto="s"
  if ! confirmar "¿Activar mTLS para el SOAP?" "$defecto"; then
    REQUIRE_MTLS="false"
    PORT_WS_MTLS="127.0.0.1:${PORT_WS_MTLS##*:}"   # sin mTLS, el 8443 no se expone
    MTLS_TEXTO="No"
    return 0
  fi

  REQUIRE_MTLS="true"
  PORT_WS_MTLS="${PORT_WS_MTLS##*:}"
  preguntar_puerto "Puerto público para el SOAP con mTLS" PORT_WS_MTLS

  # Solo se ofrece reutilizarlo si lo firmó la CA de clientes actual
  if [[ -f "$DIR_CLIENTES/$CLIENTE_MTLS.crt" ]] \
     && openssl verify -CAfile "$DIR_CA/$CA_CLIENTES.crt" "$DIR_CLIENTES/$CLIENTE_MTLS.crt" >/dev/null 2>&1 \
     && confirmar "Ya existe un certificado de cliente válido. ¿Reutilizarlo?" s; then

    ok "Se reutiliza $CLIENTE_MTLS.crt."
  else
    local cn
    preguntar_obligatorio "Nombre (CN) del primer cliente" cn "Tu Organismo"
    local huella_antigua=""
    if [[ -f "$DIR_CLIENTES/$CLIENTE_MTLS.crt" ]]; then
      huella_antigua=$(openssl x509 -noout -fingerprint -sha256 -in "$DIR_CLIENTES/$CLIENTE_MTLS.crt" \
        | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')
      AUTH_CERT_WHITELIST=";$AUTH_CERT_WHITELIST;"
      AUTH_CERT_WHITELIST="${AUTH_CERT_WHITELIST//;$huella_antigua;/;}"
      AUTH_CERT_WHITELIST="${AUTH_CERT_WHITELIST#;}"; AUTH_CERT_WHITELIST="${AUTH_CERT_WHITELIST%;}"
    fi
    firmar_con_ca "$CA_CLIENTES" "$DIR_CLIENTES/$CLIENTE_MTLS" "/CN=${cn}" 365 \
"basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth"
    export P12_PASS
    P12_PASS=$(generar_password)
    ossl pkcs12 -export -in "$DIR_CLIENTES/$CLIENTE_MTLS.crt" -inkey "$DIR_CLIENTES/$CLIENTE_MTLS.key" \
      -certfile "$DIR_CA/$CA_CLIENTES.crt" -name "$cn" \
      -out "$DIR_CLIENTES/$CLIENTE_MTLS.p12" -passout env:P12_PASS
    (umask 077; printf '%s\n' "$P12_PASS" > "$DIR_CLIENTES/$CLIENTE_MTLS.p12.pass")
    unset P12_PASS
    ok "Generado $CLIENTE_MTLS.p12 (su contraseña está en $CLIENTE_MTLS.p12.pass)."
  fi

  local huella
  huella=$(openssl x509 -noout -fingerprint -sha256 -in "$DIR_CLIENTES/$CLIENTE_MTLS.crt" \
    | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')
  if [[ -z "$AUTH_CERT_WHITELIST" ]]; then
    AUTH_CERT_WHITELIST="$huella"
  elif [[ ";$AUTH_CERT_WHITELIST;" != *";$huella;"* ]]; then
    AUTH_CERT_WHITELIST="$AUTH_CERT_WHITELIST;$huella"
  fi

  local ca_extra
  preguntar "CA adicional que emita certificados de cliente, p. ej. la del organismo (ruta; vacío = ninguna)" ca_extra ""
  if [[ -n "$ca_extra" ]]; then
    [[ -f "$ca_extra" ]] || fallo "No existe '$ca_extra'."
    openssl x509 -noout -in "$ca_extra" 2>/dev/null || fallo "'$ca_extra' no es un certificado PEM."
    cp "$ca_extra" "$DIR_CA/ca_clientes_extra.crt"
    ok "CA adicional añadida."
  elif [[ -f "$DIR_CA/ca_clientes_extra.crt" ]]; then
    info "Se mantiene la CA adicional configurada anteriormente (ca_clientes_extra.crt)."
  fi
  MTLS_TEXTO="Sí, en el puerto $PORT_WS_MTLS"
}





paso_almacenes_java() {
  titulo "6. Almacenes de claves para Jetty (backend-ws)"
  export KEYSTORE_PASSWORD TRUSTSTORE_PASSWORD
  [[ -n "$KEYSTORE_PASSWORD" ]]   || KEYSTORE_PASSWORD=$(generar_hex 16)
  [[ -n "$TRUSTSTORE_PASSWORD" ]] || TRUSTSTORE_PASSWORD=$(generar_hex 16)

  # Keystore: certificado del dominio (Jetty lo sirve en el 8443 y en el 443 interno)
  ossl pkcs12 -export -in "$DIR_PUBLICO/$CERT_FILE" -inkey "$DIR_PUBLICO/$KEY_FILE" -name jetty \
    -out "$DIR_WS/keystore.p12" -passout env:KEYSTORE_PASSWORD

  # Truststore: solo las CA que emiten certificados de cliente del SOAP
  local cas=("$DIR_CA/$CA_CLIENTES.crt")
  if [[ -f "$DIR_CA/ca_clientes_extra.crt" ]]; then
    cas+=("$DIR_CA/ca_clientes_extra.crt")
  fi
  rm -f "$DIR_WS/truststore.p12"
  if [[ "$(openssl pkcs12 -help 2>&1 || true)" == *"-jdktrust"* ]]; then
    local pem
    pem=$(mktemp)
    cat "${cas[@]}" > "$pem"
    ossl pkcs12 -export -nokeys -jdktrust anyExtendedKeyUsage -in "$pem" \
      -out "$DIR_WS/truststore.p12" -passout env:TRUSTSTORE_PASSWORD
    rm -f "$pem"
  else
    command -v keytool >/dev/null 2>&1 \
      || fallo "Para el truststore hace falta OpenSSL 3.2 o superior, o keytool (paquete default-jre-headless)."
    local i
    for i in "${!cas[@]}"; do
      keytool -importcert -noprompt -alias "ca$((i + 1))" -file "${cas[$i]}" \
        -keystore "$DIR_WS/truststore.p12" -storetype PKCS12 \
        -storepass:env TRUSTSTORE_PASSWORD >/dev/null
    done
  fi
  ok "keystore.p12 y truststore.p12 generados en $DIR_WS."
}





paso_secretos() {
  titulo "7. Credenciales y secretos"
  preguntar_obligatorio "Usuario administrador inicial" DEFAULT_ADMIN_USER \
    "${DEFAULT_ADMIN_USER:-admin@${DOMAIN_NAME#www.}}"

  if [[ -n "$SOAP_API_TOKEN" || -n "$JWT_SECRET" ]]; then
    aviso "Hay secretos de una instalación anterior. Si se regeneran, los integradores necesitarán el nuevo token SOAP y se cerrarán todas las sesiones."
    if confirmar "¿Regenerar los secretos?" n; then
      SOAP_API_TOKEN=""; JWT_SECRET=""; ALTCHA_HMAC_KEY="";
    fi
  fi
  # La contraseña inicial del administrador solo se usa al crearlo la primera vez,
  [[ -n "$SOAP_API_TOKEN" ]]         || SOAP_API_TOKEN=$(generar_hex 24)
  [[ -n "$JWT_SECRET" ]]             || JWT_SECRET=$(generar_hex 32)
  [[ -n "$ALTCHA_HMAC_KEY" ]]        || ALTCHA_HMAC_KEY=$(generar_hex 32)
  [[ -n "$GRAFANA_PASSWORD" ]]       || GRAFANA_PASSWORD=$(generar_password)
  [[ -n "$DEFAULT_ADMIN_PASSWORD" ]] || DEFAULT_ADMIN_PASSWORD=$(generar_password)
  ok "Secretos listos (se consultan en el .env)."
}




limpiar_oauth() {
  OAUTH_CLIENT_ID=""; OAUTH_CLIENT_SECRET=""; OAUTH_REFRESH_TOKEN=""; OAUTH_TOKEN_URL=""; OAUTH_SCOPE=""
}




paso_smtp() {
  titulo "8. Correo saliente"
  echo "  1) Servidor SMTP con usuario y contraseña"
  echo "  2) Servidor SMTP con OAuth2 (Gmail / Microsoft 365)"
  echo "  3) Relay del organismo sin autenticación (autoriza por IP)"
  echo "  4) No configurar correo"
  local opcion defecto="4"
  case "$SMTP_AUTH_METHOD" in
    PLAIN)  defecto="1" ;;
    OAUTH2) defecto="2" ;;
    NONE)   defecto="3" ;;
  esac
  while true; do
    preguntar "Elige una opción" opcion "$defecto"
    [[ "$opcion" =~ ^[1-4]$ ]] && break
    error "Opción no válida."
  done

  # Valores previos como sugerencia (sin los de relleno de "no configurar")
  local host_previo="$SMTP_HOST" puerto_previo="$SMTP_PORT"
  [[ "$host_previo" == "localhost" ]] && host_previo=""
  [[ "$puerto_previo" == "9999" ]] && puerto_previo=""

  case "$opcion" in
    1)
      SMTP_AUTH_METHOD="PLAIN"
      preguntar_obligatorio "Servidor SMTP" SMTP_HOST "$host_previo"
      SMTP_PORT="${puerto_previo:-587}"
      preguntar_puerto "Puerto (587 = STARTTLS, 465 = TLS)" SMTP_PORT
      preguntar_obligatorio "Usuario SMTP" SMTP_USER "$SMTP_USER"
      preguntar_secreto "Contraseña SMTP" SMTP_PASS
      limpiar_oauth
      ;;
    2)
      SMTP_AUTH_METHOD="OAUTH2"
      preguntar_obligatorio "Servidor SMTP" SMTP_HOST "${host_previo:-smtp.gmail.com}"
      SMTP_PORT="${puerto_previo:-465}"
      preguntar_puerto "Puerto (587 = STARTTLS, 465 = TLS)" SMTP_PORT
      preguntar_obligatorio "Cuenta de correo (usuario SMTP)" SMTP_USER "$SMTP_USER"
      preguntar_obligatorio "Client ID" OAUTH_CLIENT_ID "$OAUTH_CLIENT_ID"
      preguntar_secreto "Client Secret" OAUTH_CLIENT_SECRET
      preguntar_secreto "Refresh Token" OAUTH_REFRESH_TOKEN
      preguntar "OAuth2 scope (opcional)" OAUTH_SCOPE "$OAUTH_SCOPE"
      local url_defecto="$OAUTH_TOKEN_URL"
      if [[ -z "$url_defecto" && "$SMTP_HOST" == *gmail* ]]; then
        url_defecto="https://oauth2.googleapis.com/token"
      fi
      preguntar_obligatorio "URL del token OAuth2" OAUTH_TOKEN_URL "$url_defecto"
      SMTP_PASS=""
      ;;
    3)
      SMTP_AUTH_METHOD="NONE"
      aviso "El relay debe aceptar correo de la IP de este servidor."
      preguntar_obligatorio "Servidor relay del organismo" SMTP_HOST "$host_previo"
      SMTP_PORT="${puerto_previo:-25}"
      preguntar_puerto "Puerto" SMTP_PORT
      SMTP_USER=""; SMTP_PASS=""
      limpiar_oauth
      ;;
    4)
      # Mismos valores que la versión anterior: sin método y un destino inexistente
      SMTP_AUTH_METHOD=""; SMTP_HOST="localhost"; SMTP_PORT="9999"; SMTP_SECURE="false"
      SMTP_USER=""; SMTP_PASS=""; EMAIL_FROM=""
      limpiar_oauth
      CORREO_TEXTO="No configurado"
      aviso "Sin correo, las funciones que envían emails no funcionarán."
      return 0
      ;;
  esac

  preguntar_obligatorio "Remitente de los correos" EMAIL_FROM "${EMAIL_FROM:-noreply@${DOMAIN_NAME}}"
  # 465 usa TLS desde el inicio; el resto, STARTTLS
  if [[ "$SMTP_PORT" == "465" ]]; then SMTP_SECURE="true"; else SMTP_SECURE="false"; fi
  info "SMTP_SECURE=$SMTP_SECURE según el puerto $SMTP_PORT."
  CORREO_TEXTO="$SMTP_AUTH_METHOD en $SMTP_HOST:$SMTP_PORT"
}





paso_observabilidad() {
  titulo "9. Observabilidad (Grafana, Loki y Tempo)"
  local defecto="n"
  [[ "$COMPOSE_PROFILES" == *obs* ]] && defecto="s"
  if confirmar "¿Activar la observabilidad?" "$defecto"; then
    COMPOSE_PROFILES="obs"; ENABLE_TRACING="true"
    OTEL_SDK_DISABLED="false"; OTEL_TRACES_EXPORTER="otlp"
    OBS_TEXTO="Sí"
  else
    COMPOSE_PROFILES=""; ENABLE_TRACING="false"
    OTEL_SDK_DISABLED="true"; OTEL_TRACES_EXPORTER="none"
    OBS_TEXTO="No"
  fi
}





# Cada contenedor lee su directorio con el grupo CERTS_GID (group_add).
# ca/ y clientes/ solo los lee el administrador.
paso_permisos() {
  titulo "10. Permisos de la PKI"
  CERTS_GID="$(stat -c %g "$PKI_DIR")"
  local d
  for d in "$DIR_PUBLICO" "$DIR_WS" "$DIR_NER" "$DIR_APP"; do
    chgrp -R "$CERTS_GID" "$d" 2>/dev/null || aviso "No se pudo cambiar el grupo de $d."
    chmod 750 "$d"
    find "$d" -type f -exec chmod 640 {} +
  done
  chmod 700 "$DIR_CA" "$DIR_CLIENTES"
  find "$DIR_CA" "$DIR_CLIENTES" -type f -exec chmod 600 {} +
  ok "Permisos aplicados (grupo $CERTS_GID)."
}






paso_resumen() {
  titulo "Resumen"
  echo "  Dominio:          $DOMAIN_NAME (HTTP $PORT_PROXY_HTTP / HTTPS $PORT_PROXY_HTTPS)"
  echo "  Certificado:      $MODO_SSL ($CERT_FILE)"
  echo "  SOAP con mTLS:    $MTLS_TEXTO"
  echo "  Administrador:    $DEFAULT_ADMIN_USER"
  echo "  Correo:           $CORREO_TEXTO"
  echo "  Observabilidad:   $OBS_TEXTO"
  echo "  PKI en:           $PKI_DIR"
  echo
  confirmar "¿Generar el .env con estos datos?" s || fallo "Cancelado. El .env no se ha modificado."
}





seccion_env() {
  local titulo_seccion="$1" clave
  shift
  printf '\n# %s\n' "$titulo_seccion"
  for clave in "$@"; do
    printf "%s='%s'\n" "$clave" "${!clave}"
  done
}





escribir_env() {
  titulo "Generando .env"
  if [[ -f "$ENV_FILE" ]]; then
    local copia
    copia="$ENV_FILE.bak.$(date +%Y%m%d-%H%M%S)"
    cp -p "$ENV_FILE" "$copia"
    chmod 600 "$copia"
    info "Copia del .env anterior: $(basename "$copia")"
  fi

  local tmp clave
  tmp="$(mktemp "$REPO_DIR/.env.XXXXXX")"
  {
    echo "# Generado por env.sh el $(date '+%Y-%m-%d %H:%M')."
    echo "# Valores entre comillas simples para que docker compose no interprete caracteres como \$."
    seccion_env "Dominio y proxy" DOMAIN_NAME CERT_FILE KEY_FILE PORT_PROXY_HTTP PORT_PROXY_HTTPS
    seccion_env "Certificados (grupo con acceso a ../pki)" CERTS_GID KEYSTORE_PASSWORD TRUSTSTORE_PASSWORD
    seccion_env "SOAP y mTLS" SOAP_API_TOKEN REQUIRE_MTLS AUTH_CERT_WHITELIST PORT_WS_MTLS
    seccion_env "Servicio NER" ENV LOG_LEVEL ENFORCE_SECURITY
    seccion_env "Aplicación" JWT_SECRET ALTCHA_HMAC_KEY DEFAULT_ADMIN_USER DEFAULT_ADMIN_PASSWORD HARD_MAINTENANCE
    seccion_env "Correo" SMTP_AUTH_METHOD SMTP_HOST SMTP_PORT SMTP_SECURE SMTP_USER SMTP_PASS EMAIL_FROM \
      OAUTH_CLIENT_ID OAUTH_CLIENT_SECRET OAUTH_REFRESH_TOKEN OAUTH_TOKEN_URL OAUTH_SCOPE
    seccion_env "Observabilidad" COMPOSE_PROFILES ENABLE_TRACING OTEL_SDK_DISABLED OTEL_TRACES_EXPORTER GRAFANA_PASSWORD
  } > "$tmp"

  # Comprobación: todas las variables están en el fichero
  for clave in "${VARIABLES_ENV[@]}"; do
    grep -q "^${clave}=" "$tmp" || { rm -f "$tmp"; fallo "Falta $clave en el .env (error del asistente)."; }
  done

  chmod 640 "$tmp"   # legible por el grupo de administración
  mv "$tmp" "$ENV_FILE"
  ok ".env generado."
}





# Valida el compose con el .env recién generado: falla si falta una variable obligatoria
verificar_compose() {
  titulo "Comprobando docker-compose.yml con el nuevo .env"
  local salida
  if ! salida=$(docker compose config --quiet 2>&1); then
    error "docker compose no acepta la configuración:"
    echo "$salida" >&2
    exit 1
  fi
  if [[ "$salida" == *"is not set"* ]]; then
    aviso "El compose usa variables que el .env no define:"
    echo "$salida"
  else
    ok "Todas las variables que usa el compose están definidas."
  fi
}

aviso_final() {
  echo
  echo -e "${AMARILLO}=================================================================${NC}"
  echo -e "${AMARILLO} Las credenciales generadas no se muestran por pantalla.${NC}"
  echo -e " Consúltalas en .env:"
  echo -e "   DEFAULT_ADMIN_PASSWORD   contraseña inicial del administrador"
  echo -e "   SOAP_API_TOKEN           token para los integradores"
  if [[ "$OBS_TEXTO" == "Sí" ]]; then
    echo -e "   GRAFANA_PASSWORD         usuario admin de Grafana"
  fi
  if [[ "$REQUIRE_MTLS" == "true" ]]; then
    echo -e " Certificado de cliente: $DIR_CLIENTES/$CLIENTE_MTLS.p12"
    echo -e "   (su contraseña está en $CLIENTE_MTLS.p12.pass; entrégalos por canales distintos)"
  fi
  if [[ "$MODO_SSL" == Autofirmado* ]]; then
    echo -e " Importa $DIR_CA/$CA_INTERNA.crt como CA de confianza en los equipos que usen la web."
  fi
  echo
  echo -e "${VERDE} Siguiente paso: docker compose up -d --build${NC}"
  echo -e "${VERDE} o, si ha cambiado el .env: docker compose up -d --build --force-recreate${NC}"
  echo -e "${AMARILLO}=================================================================${NC}"
}






# =============================================================================
# Principal
# =============================================================================

main() {
  echo -e "${CIAN}====================================================${NC}"
  echo -e "${CIAN} ASISTENTE DE INSTALACIÓN DE SENSIA${NC}"
  echo -e "${CIAN}====================================================${NC}"

  comprobar_dependencias
  preparar_directorios

  valores_por_defecto
  
  if [[ -f "$EJEMPLO_FILE" ]]; then
    cargar_fichero "$EJEMPLO_FILE" sin_secretos
    info "Valores por defecto tomados de .env.example."
  else
    aviso "No se encuentra .env.example; se usan los valores por defecto del script."
  fi

  if [[ -f "$ENV_FILE" ]]; then
    aviso "Ya existe un .env en esta instalación."
    if ! confirmar "¿Reconfigurar? Sus valores se usarán como punto de partida" n; then
      ok "Sin cambios."
      exit 0
    fi
    cargar_fichero "$ENV_FILE"
  fi

  paso_dominio
  paso_ca
  paso_ssl
  paso_certificados_internos
  paso_mtls
  paso_almacenes_java
  paso_secretos
  paso_smtp
  paso_observabilidad
  paso_permisos
  paso_resumen
  escribir_env
  verificar_compose
  aviso_final
}

main "$@"
