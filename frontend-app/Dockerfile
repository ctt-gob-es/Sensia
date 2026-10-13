# syntax=docker/dockerfile:1
# =============================================================================
# frontend-app: Angular servido con Caddy
# =============================================================================

# ---------- Etapa 1: compilación ----------
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration=production

# ---------- Etapa 2: servidor de estáticos ----------
FROM caddy:2.10-alpine

# envsubst para generar env.json al arrancar.
# Se quita la capacidad de fichero de caddy: con no-new-privileges impediría
# arrancarlo como usuario normal, y no hace falta porque Docker ya permite
# abrir el puerto 80 sin privilegios dentro del contenedor.
RUN apk add --no-cache gettext \
 && apk add --no-cache --virtual .setcap libcap \
 && (setcap -r /usr/bin/caddy || true) \
 && apk del .setcap \
 && addgroup -S -g 10001 web \
 && adduser -S -u 10001 -G web -H web

COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist/sensia-app/browser /usr/share/caddy

# env.json es lo único escribible junto con /data y /config
RUN touch /usr/share/caddy/env.json \
 && chown web:web /usr/share/caddy/env.json \
 && chown -R web:web /data /config
USER web

EXPOSE 80
# Solo se sustituyen las dos variables previstas; exec deja a caddy como proceso 1
CMD ["sh", "-c", "envsubst '${API_URL} ${ENABLE_TRACING}' < /usr/share/caddy/env.production.json > /usr/share/caddy/env.json && exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile"]