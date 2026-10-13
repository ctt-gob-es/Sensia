FROM rnwood/smtp4dev:v3

USER root

COPY keycloak/certs/keycloak.crt /usr/local/share/ca-certificates/keycloak.crt

RUN update-ca-certificates

