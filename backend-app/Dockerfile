# syntax=docker/dockerfile:1
# =============================================================================
# backend-app: API Node.js
# =============================================================================

# Etapa 1: dependencias
FROM node:24-alpine AS deps
WORKDIR /usr/src/app
COPY package*.json ./

RUN npm ci --omit=dev

# Etapa 2: ejecución
FROM node:24-alpine
RUN apk add --no-cache tini
WORKDIR /usr/src/app

ENV NODE_ENV=production \
    PORT=3000

COPY --from=deps /usr/src/app/node_modules ./node_modules
COPY package*.json ./
COPY src/ ./
RUN mkdir -p bdd logs && chown node:node bdd logs

USER node
EXPOSE 3000

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "--require", "./tracing.js", "server.js"]

