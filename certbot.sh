#!/usr/bin/env bash
# =============================================================================
# SensIA - certificado de Let's Encrypt (opcional, solo en la instalación)
#
# Lo usa env.sh en la opción 3; deja fullchain.pem y privkey.pem en SALIDA:
#   SALIDA=/opt/sensia/pki/publico ./certbot.sh dominio.es admin@dominio.es [--staging]
#
# Renovar a mano, la instalación no lo programa. Deben gestionarlo los técnicos de la entidad.
# El siguiente comando solo actúa si certbot decide que toca
#   ./certbot.sh renovar
#
# Si el proxy está en marcha se valida por webroot y no se corta el servicio.
# Si no lo está (primera instalación), certbot atiende él mismo el puerto 80.
# Let's Encrypt valida siempre por el puerto 80 accesible desde internet.
# =============================================================================
set -Eeuo pipefail

cd "$(dirname "$0")"
REPO_DIR="$(pwd)"
ENV_FILE="$REPO_DIR/.env"

IMAGEN_CERTBOT="certbot/certbot:v5.8.0"
VOLUMEN_DATOS="certbot-data"                      # cuenta y certificados de Let's Encrypt
WEBROOT="/var/www/certbot"                        # lo sirve nginx en /.well-known/acme-challenge/
PROXY="${PROXY:-proxy-nginx}"
SALIDA="${SALIDA:-$(cd .. && pwd)/pki/publico}"   # certificado del dominio (proxy-nginx)
DIR_WS="${DIR_WS:-$(cd .. && pwd)/pki/ws}"        # keystore de Jetty (backend-ws)

METODO=""
METODO_ARGS=()

log()   { echo "$(date '+%F %T') $*"; }
info()  { log "[*] $*"; }
ok()    { log "[OK] $*"; }
error() { log "[!] $*" >&2; }
fallo() { error "$*"; exit 1; }

trap 'error "Error inesperado en la línea $LINENO."' ERR




# Lee una variable del .env (admite valores con o sin comillas)
leer_env() {
  local clave="$1" linea
  linea=$(grep -E "^${clave}=" "$ENV_FILE" | tail -n 1) || return 0
  linea="${linea#*=}"
  if [[ "$linea" == \'*\' || "$linea" == \"*\" ]]; then
    linea="${linea:1:${#linea}-2}"
  fi
  printf '%s' "$linea"
}




servicio_en_marcha() {
  local servicios
  servicios=$(docker compose ps --status running --services 2>/dev/null || true)
  grep -qx "$1" <<< "$servicios"
}




# Webroot si el proxy está en marcha; si no, certbot escucha en el puerto 80
elegir_metodo() {
  if servicio_en_marcha "$PROXY"; then
    METODO="webroot"
    METODO_ARGS=(--webroot -w "$WEBROOT")
  else
    METODO="standalone"
    METODO_ARGS=(--standalone)
  fi
  info "Validación por $METODO."
}




volumen_webroot() {
  local volumenes
  volumenes=$(docker volume ls -q --filter "label=com.docker.compose.volume=acme-webroot")
  [[ -n "$volumenes" ]] || fallo "No se encuentra el volumen acme-webroot del proxy."
  [[ $(wc -l <<< "$volumenes") -eq 1 ]] || fallo "Hay varios volúmenes acme-webroot en este servidor: $volumenes"
  printf '%s' "$volumenes"
}




# Ejecuta certbot en un contenedor efímero con el método elegido
certbot_docker() {
  local args=(--rm -v "$VOLUMEN_DATOS:/etc/letsencrypt")
  if [[ "$METODO" == "webroot" ]]; then
    local vol
    vol=$(volumen_webroot)
    args+=(-v "$vol:$WEBROOT")
  else
    args+=(-p 80:80)
  fi
  docker run "${args[@]}" "$IMAGEN_CERTBOT" "$@"
}





# Copia fullchain.pem y privkey.pem del volumen de certbot a un directorio
extraer_certificado() {
  local dominio="$1" destino="$2"
  docker run --rm --entrypoint sh \
    -v "$VOLUMEN_DATOS:/etc/letsencrypt:ro" -v "$destino:/salida" \
    -e DOMINIO="$dominio" -e PROPIETARIO="$(id -u):$(id -g)" \
    "$IMAGEN_CERTBOT" -c '
      set -e
      cp -L "/etc/letsencrypt/live/$DOMINIO/fullchain.pem" /salida/fullchain.pem
      cp -L "/etc/letsencrypt/live/$DOMINIO/privkey.pem"  /salida/privkey.pem
      chown "$PROPIETARIO" /salida/fullchain.pem /salida/privkey.pem
    '
}




# Deja el certificado en SALIDA solo si ha cambiado. Devuelve 1 si no hay cambios.
instalar_si_cambia() {
  local dominio="$1" tmp grupo
  mkdir -p "$SALIDA"
  tmp=$(mktemp -d "$SALIDA/.letsencrypt.XXXXXX")
  extraer_certificado "$dominio" "$tmp"
  [[ -s "$tmp/fullchain.pem" && -s "$tmp/privkey.pem" ]] \
    || { rm -rf "$tmp"; fallo "No se pudo extraer el certificado de $dominio."; }
  if [[ -f "$SALIDA/fullchain.pem" ]] && cmp -s "$tmp/fullchain.pem" "$SALIDA/fullchain.pem"; then
    rm -rf "$tmp"
    return 1
  fi
  grupo=$(stat -c %g "$SALIDA")
  chgrp "$grupo" "$tmp/fullchain.pem" "$tmp/privkey.pem" 2>/dev/null || true
  chmod 644 "$tmp/fullchain.pem"
  chmod 640 "$tmp/privkey.pem"     # mismos permisos que aplica env.sh
  mv -f "$tmp/privkey.pem" "$SALIDA/privkey.pem"     || fallo "No se pudo instalar privkey.pem."
  mv -f "$tmp/fullchain.pem" "$SALIDA/fullchain.pem" || fallo "No se pudo instalar fullchain.pem."
  rmdir "$tmp"
}




# Tras renovar: regenera el keystore de Jetty y recarga los servicios afectados
aplicar_certificado() {
  local tmp grupo
  export KEYSTORE_PASSWORD
  KEYSTORE_PASSWORD=$(leer_env KEYSTORE_PASSWORD)
  [[ -n "$KEYSTORE_PASSWORD" ]] || fallo "No se encuentra KEYSTORE_PASSWORD en el .env."

  info "Regenerando keystore.p12 (backend-ws)..."
  tmp=$(mktemp "$DIR_WS/.keystore.XXXXXX")
  openssl pkcs12 -export -in "$SALIDA/fullchain.pem" -inkey "$SALIDA/privkey.pem" \
    -name jetty -out "$tmp" -passout env:KEYSTORE_PASSWORD
  grupo=$(stat -c %g "$DIR_WS")
  chgrp "$grupo" "$tmp" 2>/dev/null || true
  chmod 640 "$tmp"
  mv -f "$tmp" "$DIR_WS/keystore.p12"

  if servicio_en_marcha "$PROXY"; then
    info "Recargando nginx (sin cortar conexiones)..."
    docker compose exec -T "$PROXY" nginx -s reload
  fi
  
  # El 8443 (TLS de extremo a extremo) usa el keystore en cualquier modo
  if servicio_en_marcha backend-ws; then
    info "Reiniciando backend-ws para que cargue el nuevo certificado..."
    docker compose restart backend-ws
  fi
}




emitir() {
  local dominio="$1" email="$2" staging="${3-}"
  local extra=()
  case "$staging" in
    "") ;;
    --staging) extra+=(--staging) ;;
    *) fallo "Tercer parámetro no válido: $staging (solo se admite --staging)." ;;
  esac

  # Si existía un certificado de pruebas y ahora se pide el real, se descarta el de pruebas
  if [[ -z "$staging" ]]; then
    docker run --rm --entrypoint sh -v "$VOLUMEN_DATOS:/etc/letsencrypt" \
      -e DOMINIO="$dominio" "$IMAGEN_CERTBOT" -c '
        if grep -qs acme-staging "/etc/letsencrypt/renewal/$DOMINIO.conf"; then
          certbot delete --cert-name "$DOMINIO" --non-interactive
        fi
      '
  fi

  elegir_metodo
  info "Solicitando certificado para $dominio..."
  certbot_docker certonly "${METODO_ARGS[@]}" \
    --non-interactive --agree-tos --no-eff-email -m "$email" \
    -d "$dominio" --cert-name "$dominio" --keep-until-expiring "${extra[@]}"

  if instalar_si_cambia "$dominio"; then
    ok "Certificado guardado en $SALIDA (fullchain.pem y privkey.pem)."
  else
    ok "El certificado vigente sigue siendo válido; no hay cambios."
  fi
}





renovar() {
  [[ -f "$ENV_FILE" ]] || fallo "No existe .env; ejecuta antes ./env.sh."
  if [[ "$(leer_env CERT_FILE)" != "fullchain.pem" ]]; then
    info "Esta instalación no usa Let's Encrypt (CERT_FILE no es fullchain.pem). Nada que hacer."
    return 0
  fi
  local dominio
  dominio=$(leer_env DOMAIN_NAME)
  elegir_metodo
  # certbot decide si toca renovar; el método de validación queda guardado para la próxima vez
  certbot_docker renew --cert-name "$dominio" "${METODO_ARGS[@]}" --non-interactive
  if instalar_si_cambia "$dominio"; then
    ok "Certificado renovado."
    aplicar_certificado
  else
    ok "No tocaba renovar."
  fi
}





main() {
  command -v docker >/dev/null 2>&1 || fallo "Falta docker."
  command -v flock  >/dev/null 2>&1 || fallo "Falta flock (paquete util-linux)."
  exec 9>"$REPO_DIR/.certbot.lock"
  flock -n 9 || fallo "Ya hay otra ejecución de certbot.sh en curso."

  if [[ "${1-}" == "renovar" ]]; then
    renovar
  elif [[ $# -ge 2 ]]; then
    emitir "$@"
  else
    fallo "Uso: $0 <dominio> <email> [--staging]   |   $0 renovar"
  fi
}

main "$@"