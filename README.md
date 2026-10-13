# SensIA - Sensor Inteligente de Anonimización

SensIA es una solución de anonimización de documentos PDF impulsada por IA y visión artificial. Diseñada bajo un enfoque Human-in-the-Loop (supervisión humana integrada). Está pensada específicamente para facilitar el cumplimiento normativo en las Administraciones Públicas.

**Estado del proyecto:** versión candidata 1.0.0-rc.1, probada en un piloto en un organismo público y pendiente de despliegue generalizado.

*SensIA cuenta con cobertura de tests y ha mostrado una tasa de detección alta durante el piloto. No obstante, se han identificado omisiones en casos concretos, por lo que se recomienda validarla en un entorno preproductivo con documentos propios antes de usarla en producción, y mantener en todo caso la revisión humana de los resultados.*

**Limitaciones conocidas**

- **Códigos QR adyacentes a tablas:** algunos códigos situados junto a bordes o líneas de tabla pueden no detectarse.
- **Texto en tablas con saltos de línea:** cuando un dato se reparte en varias líneas dentro de una celda, el PDF lo almacena como fragmentos separados y puede no detectarse completo.
- **Documentos escaneados de baja resolución:** el OCR puede no reconocer todo el texto y omitir parte de los datos.
- **Imágenes con texto:** el texto contenido en imágenes de baja resolución incrustadas en el PDF puede no anonimizarse.
- **PDF mixtos (páginas nativas y escaneadas):** la decisión de aplicar OCR se toma con la primera página. Si es nativa, las páginas escaneadas posteriores no se analizan y su texto no se anonimiza. Revísalas en la pantalla de revisión o envía las páginas escaneadas como un PDF aparte.
- **Rostros girados o muy pequeños:** los rostros girados (por ejemplo, en fotografías de documentos de identidad escaneadas de lado) o muy pequeños dentro de imágenes de gran tamaño pueden no detectarse.
  
En estos casos, la pantalla de revisión permite añadir marcas manuales. En documentos basados en plantilla, las censuras fijas, la lista negra o la eliminación de imágenes cubren las zonas recurrentes. Estas limitaciones se abordarán en próximas versiones; se agradece el reporte de nuevos casos mediante *issues*.

---

## Arquitectura

```
Navegador / integradores
        │  siempre HTTPS
        ▼
proxy-nginx ── :443  /            → frontend-app    (Angular, Caddy)
            ── :443  /api/        → backend-app     (Node.js) ──HTTPS──► backend-ws:443
            ── :443  /services/   → backend-ws:443  (SOAP con token)
            ── :8443 passthrough  → backend-ws:8443 (SOAP con certificado + token)
            ── :80   solo redirige la web a HTTPS; /api/ y /services/ → 403

backend-ws ──gRPC con mTLS──► backend-ner (modelo NER en ONNX)
```

| Servicio | Función |
|---|---|
| `proxy-nginx` | Única entrada: TLS, enrutado y cabeceras de seguridad |
| `frontend-app` | Aplicación web (Angular servida con Caddy) |
| `backend-app` | API de la web: usuarios, plantillas, correo; llama al servicio SOAP |
| `backend-ws` | Servicio SOAP de anonimización (Jetty + Apache CXF, OpenCV, Tesseract) |
| `backend-ner` | Reconocimiento de entidades (modelo RoBERTa en ONNX) por gRPC |
| `grafana`, `loki`, `tempo`, `alloy`, `docker-proxy` | Observabilidad (opcional, perfil `obs`) |

**Principios del despliegue**

- nginx es la única entrada. Ningún otro servicio publica puertos hacia fuera.
- Todo el tráfico externo va por HTTPS. El HTTP solo redirige la web; la API y el SOAP lo rechazan.
- El SOAP admite dos vías: **token** (443) y **certificado de cliente + token** (8443). Con `REQUIRE_MTLS=true` la vía del 443 se cierra (403) y solo queda la del 8443. En esa vía, nginx no descifra la conexión: el certificado lo valida el propio Jetty.
- Los contenedores corren sin privilegios de root (salvo nginx y docker-proxy, que accede al socket de Docker), con `no-new-privileges` y sin capacidades del kernel innecesarias.

---

## Requisitos

- **Servidor:** probado en amd64 con 4 vCPU, 8 GB de memoria y Ubuntu 26.04. Con la observabilidad activa, se recomiendan 16 GB.
- **Disco:** reserva al menos 30 GB libres; la construcción de las imágenes (OpenCV, exportación del modelo) ocupa bastante.
- **Docker Engine** reciente y **Docker Compose v2.24** o superior.
- **Salida a internet durante la construcción:** GitHub, Hugging Face, npm, Maven Central, PyPI, download.pytorch.org, Docker Hub y los repositorios de Ubuntu.
- **Para Let's Encrypt:** el dominio debe resolver al servidor y el puerto 80 debe ser accesible desde internet.

---

## Instalación

### 1. Docker

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y ca-certificates curl gnupg git openssl

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

**Subredes de Docker.** Docker asigna por defecto subredes de los rangos 172.17–172.31.x.x y 192.168.x.x. Si coinciden con rangos del organismo (usuarios, VPN, servidores), la aplicación dejará de responder desde esas redes. Antes de desplegar, acuerda con el equipo de red un rango reservado y fíjalo en `/etc/docker/daemon.json` (ejemplo; los rangos deben ser los que os asignen):

```json
{
  "bip": "10.200.0.1/24",
  "default-address-pools": [
    { "base": "10.201.0.0/16", "size": 24 }
  ]
}
```

```bash
sudo systemctl restart docker
```

### 2. Usuario y directorios

```bash
sudo groupadd sensia-admins
sudo usermod -aG sensia-admins,docker $USER
sudo mkdir -p /opt/sensia
sudo chown root:sensia-admins /opt/sensia
sudo chmod 2775 /opt/sensia
```

Cierra la sesión y vuelve a entrar para que se apliquen los grupos. Comprueba que `id` muestra `docker` y `sensia-admins`.

### 3. Código

```bash
cd /opt/sensia
git clone https://github.com/ctt-gob-es/sensia sensia.git
```

```
/opt/sensia/
├── sensia.git/     repositorio, .env y docker-compose.yml
└── pki/            la genera env.sh
    ├── ca/         claves de las CA. No se monta en ningún contenedor
    ├── clientes/   certificados de cliente para entregar. No se monta
    ├── publico/    certificado del dominio → proxy-nginx
    ├── ws/  ner/  app/   lo que usa cada servicio
```

### 4. Configuración

Si tu organismo tiene valores fijos (dominio, servidor de correo, puertos…), ajústalos antes en `.env.example`: el asistente los usa como valores por defecto. Los secretos de ese fichero se ignoran siempre.

```bash
cd /opt/sensia/sensia.git
./env.sh
```

El asistente pregunta, en este orden:

1. **Dominio y puertos** publicados.
2. **Autoridades de certificación**: se generan la primera vez y se reutilizan después. Son dos: la CA interna firma los certificados internos y, con la opción 1, el del dominio; la CA de clientes firma los certificados de cliente del SOAP.
3. **Certificado HTTPS del dominio**:
   - *Autofirmado* con la CA interna (pruebas o intranet). Hay que importar `pki/ca/ca_interna.crt` como CA de confianza en los equipos que usen la web.
   - *Certificado del organismo*: PEM con la cadena intermedia y clave sin contraseña. El asistente comprueba que la clave corresponde al certificado, la caducidad y el nombre.
   - *Let's Encrypt*: lo obtiene con `certbot.sh`. La instalación no programa su renovación (ver «Renovación manual de certificados»).
4. **Certificados internos** para la comunicación gRPC entre `backend-ws` y `backend-ner`.
5. **SOAP con certificado de cliente (mTLS)**: genera el primer certificado de cliente (`cliente_mtls.p12`, con su contraseña en `cliente_mtls.p12.pass`) y la lista de huellas autorizadas. Opcionalmente admite una CA adicional, por ejemplo la del organismo.
6. **Almacenes de claves de Java** para Jetty.
7. **Credenciales y secretos**: se generan automáticamente.
8. **Correo**: usuario y contraseña, OAuth2, relay del organismo sin autenticación, o sin correo.
9. **Observabilidad** (opcional).

Al terminar, el asistente escribe el `.env` (con copia del anterior en `.env.bak.*`) y comprueba que `docker compose` acepta la configuración.

Se puede volver a ejecutar en cualquier momento: reutiliza las dos CA, el certificado de cliente y los secretos existentes, salvo que se pida regenerarlos. Regenerar el token SOAP obliga a entregar el nuevo a los integradores.

Las credenciales no se muestran por pantalla. Consúltalas en `.env`:

- `DEFAULT_ADMIN_PASSWORD`: contraseña inicial del administrador. Cámbiala tras el primer acceso.
- `SOAP_API_TOKEN`: token para los integradores.
- `GRAFANA_PASSWORD`: administrador de Grafana, si se activa la observabilidad.

### 5. Arranque

```bash
docker compose up -d --build
docker compose ps        # todos los servicios deben aparecer como "healthy"
```

La primera construcción tarda: compila OpenCV y descarga/exporta el modelo NER.

### 6. Verificación

Estados de `docker compose ps`:
- `Up (healthy)`: el healthcheck pasa.
- `health: starting`: el servicio está dentro del periodo de arranque (backend-ws unos 90 s, backend-ner unos 120 s).
- `Restarting`: el proceso termina con error y Docker lo relanza. Revisa `docker compose logs <servicio>`.

Los healthchecks solo comprueban que el puerto responde; la prueba real es anonimizar un documento.

```bash
cd /opt/sensia/sensia.git
docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
```

Las pruebas se hacen desde el servidor. nginx solo atiende al nombre del dominio: si aún no resuelve por DNS, añade `127.0.0.1 dominio` a `/etc/hosts`.

```bash
D=sensia.tuorganismo.es                            # tu dominio
CA="--cacert /opt/sensia/pki/ca/ca_interna.crt"    # solo con certificado autofirmado; si no, CA=""
```

**Proxy**
```bash
curl -sI http://$D/ | head -1                                                       # 301
curl -s -o /dev/null -w '%{http_code}\n' http://$D/api/                             # 403
curl -s -o /dev/null -w '%{http_code}\n' "http://$D/services/SensiaService?wsdl"    # 403
curl -s -o /dev/null -w '%{http_code}\n' $CA https://$D/                            # 200
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1/                        # 000 (nombre desconocido)
```

**SOAP con token (443)**
```bash
curl -s $CA "https://$D/services/SensiaService?wsdl" | grep soap:address             # dirección https
curl -s $CA "https://$D/services/SensiaService?wsdl" | grep -oE 'operation name="[^"]+"' | sort -u
```
Para invocar operaciones, importa el WSDL en SoapUI o Postman y añade la cabecera `AuthHeader` (ver «Uso del servicio SOAP»). Con un token incorrecto debe responder con un SOAP Fault.

**backend-app → backend-ws** (red interna, alias del dominio y CA interna)
```bash
docker compose exec backend-app node -e "fetch('https://$D/services/SensiaService?wsdl').then(r=>console.log(r.status)).catch(e=>{console.error(e.cause||e);process.exit(1)})"   # 200
```

**backend-ws → backend-ner (gRPC con mTLS)**
```bash
docker run --rm --network sensia_net --user "$(id -u):$(stat -c %g /opt/sensia/pki)" \
  -v /opt/sensia/pki/ws:/c:ro alpine/openssl s_client -connect backend-ner:50051 -servername backend-ner \
  -CAfile /c/ca.crt -cert /c/ws_client.crt -key /c/ws_client.key -alpn h2 </dev/null 2>&1 \
  | grep -E 'Verify return code|ALPN'               # "0 (ok)" y "h2"
```

**SOAP con certificado (8443)**, solo si se activó mTLS:
```bash
curl -s $CA --cert /opt/sensia/pki/clientes/cliente_mtls.crt --key /opt/sensia/pki/clientes/cliente_mtls.key \
     "https://$D:8443/services/SensiaService?wsdl" | head -3
```
Sin `--cert` ni `--key`, la conexión debe rechazarse.

**Exposición y privilegios**
```bash
docker compose ps --format '{{.Service}} {{.Ports}}' | grep 0.0.0.0      # solo proxy-nginx
for s in backend-app backend-ws backend-ner; do echo -n "$s: "; docker compose exec -T $s id -u; done   # ninguno debe ser 0
```

**Prueba funcional:** entra en `https://dominio` con `DEFAULT_ADMIN_USER` y `DEFAULT_ADMIN_PASSWORD` del `.env`, anonimiza un PDF y revisa `docker compose logs backend-ws backend-ner`.

### Confiar en la CA interna (certificado autofirmado)

Con la opción 1 de `env.sh`, los equipos que usen la web deben confiar en `pki/ca/ca_interna.crt`. Distribuye solo el `.crt`: `ca_interna.key` no debe salir del servidor.

> Esta CA no tiene restricción de nombres: un equipo que la importe confiará en cualquier certificado que firme, sea del dominio que sea. Úsala solo en pruebas o intranet y mantén `pki/ca` con permisos 700/600. En producción, usa el certificado del organismo o Let's Encrypt.

| Sistema o navegador | Cómo importarla |
|---|---|
| Ubuntu/Debian (sistema, curl) | `sudo cp ca_interna.crt /usr/local/share/ca-certificates/sensia-ca-interna.crt && sudo update-ca-certificates` |
| Chrome/Chromium en Linux | `certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "SensIA CA interna" -i ca_interna.crt` (paquete `libnss3-tools`) |
| Firefox (cualquier sistema) | Ajustes → Privacidad y seguridad → Certificados → Ver certificados → Autoridades → Importar → marcar «Confiar en esta CA para identificar sitios web» |
| Windows (Chrome, Edge) | En una consola de administrador: `certutil -addstore -f Root ca_interna.crt`. En un dominio de Active Directory, distribúyela por GPO |
| macOS | `sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ca_interna.crt` |

Reinicia el navegador tras importarla. Si los equipos no resuelven el dominio por DNS, añade `IP_DEL_SERVIDOR dominio` a su fichero de hosts.

---

## Uso del servicio SOAP

| Vía | Dirección | Autenticación |
|---|---|---|
| Token (443; cerrada, con 403, si `REQUIRE_MTLS=true`) | `https://dominio/services/SensiaService` | Token en la cabecera SOAP |
| Certificado (8443, solo con `REQUIRE_MTLS=true`) | `https://dominio:8443/services/SensiaService` | Certificado de cliente autorizado + token |

El WSDL está en la misma dirección con `?wsdl`. El token va en la cabecera SOAP:

```xml
<soapenv:Header>
  <sen:AuthHeader xmlns:sen="http://sensia.github.io/">
    <token>VALOR_DE_SOAP_API_TOKEN</token>
    <cliente>IDENTIFICADOR_DEL_USUARIO_O_APLICACION</cliente>
  </sen:AuthHeader>
</soapenv:Header>
```
cliente es obligatorio (1 a 128 caracteres). Cada cliente puede tener como máximo 2 documentos en proceso, y un lote (submitBatch) admite como máximo 2 documentos. Si se supera, la respuesta lleva cause = SATURADO y se puede reintentar más tarde.

**Añadir un cliente con certificado**

1. Emite el certificado con la CA de clientes (o usa uno de la CA adicional configurada en `env.sh`):
```bash
  cd /opt/sensia/pki/clientes
  openssl genrsa -out cliente2.key 2048
  openssl req -new -key cliente2.key -subj "/CN=Organismo 2" -out cliente2.csr
  printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n' > cliente2.ext
  openssl x509 -req -in cliente2.csr -CA ../ca/ca_clientes.crt -CAkey ../ca/ca_clientes.key -CAcreateserial \
    -days 365 -sha256 -extfile cliente2.ext -out cliente2.crt
  openssl pkcs12 -export -in cliente2.crt -inkey cliente2.key -certfile ../ca/ca_clientes.crt -out cliente2.p12
  rm cliente2.csr cliente2.ext
```
2. Calcula su huella y añádela a `AUTH_CERT_WHITELIST` en `.env`, separada de las demás por `;`:
```bash
   openssl x509 -noout -fingerprint -sha256 -in cliente2.crt | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f'
```
3. Aplica el cambio: `docker compose up -d backend-ws`.

Para **revocar** un cliente, quita su huella de la lista y vuelve a aplicar el cambio.

Para **limitar el acceso al 8443** a las redes de los integradores, descomenta y ajusta `allow`/`deny` en `proxy/soap-tls.stream-template`. Docker abre sus puertos por delante de `ufw`, así que el cortafuegos del sistema no sirve para esto; usa nginx o el cortafuegos perimetral del organismo.

---

## Operación

### Tamaño máximo de documento

El límite está en cuatro sitios y hay que mantenerlos coherentes. Los PDF viajan en base64, que ocupa 4/3 de su tamaño:

| Dónde | Valor para 50 MB |
|---|---|
| `docker-compose.yml`, backend-app → `MAX_UPLOAD_MB` (la web lo recibe de `/api/auth/status`) | `50` |
| `docker-compose.yml`, backend-app → `MAX_BODY_SIZE` | `70mb` |
| `docker-compose.yml`, backend-ws → `JETTY_HTTP_MAXPOSTSIZE` | `73400320` (70 MB en bytes) |
| `proxy/nginx.conf`, `/api/` y `/services/` → `client_max_body_size` | `75m` |

### Parámetros de procesamiento

Los parámetros de `backend-ws` (`app.properties`, `server.properties`, `security.properties`) se pueden cambiar sin recompilar:

- **Algunos valores:** con variables de entorno en el compose de `backend-ws`. El nombre es la clave en mayúsculas con `_` en lugar de puntos; por ejemplo, `processing.document.redactNERScore` se convierte en `PROCESSING_DOCUMENT_REDACTNERSCORE`.
- **Un perfil completo:** montando el fichero en `/app/app.properties`. Debe ser una **copia completa** del original: si existe, sustituye al interno y las claves que falten toman el valor por defecto del código.

`processing.document.logoExclusionZone` define una zona del documento que no se anonimiza, pensada para preservar el logotipo. Ajústala a vuestras plantillas.

### Logs

```bash
docker compose logs -f backend-ws        # o cualquier otro servicio
```

- `backend-app` escribe además ficheros en el volumen `backend-logs`, con rotación.
- `backend-ws` escribe además ficheros dentro del contenedor (`/app/logs`), con rotación y un máximo de 200 MB.
- **Registro de datos sensibles** (`SENSIA_LOG_SENSIBLE`): desactivado (`OFF`). Solo debe activarse para diagnósticos puntuales, con un nivel de logback (`DEBUG`), nunca con `true`/`false`. Los ficheros se guardan como máximo 2 días dentro del contenedor y desaparecen al recrearlo. Vuelve a `OFF` al terminar.
- La aplicación web sirve desde su propia imagen las fuentes, el worker de pdf.js y sus CMaps, y el navegador no descarga nada de terceros. La única excepción son los iconos de las notas de los PDF: si un PDF contiene anotaciones de tipo nota, el visor descarga su icono de `unpkg.com`. Esa petición no envía el documento ni su texto, salvo el nombre del icono que figura en la propia nota (normalmente un valor fijo, como «Note»), pero revela a ese servidor la IP del usuario y el dominio de la instalación.

### Observabilidad

Se activa desde `env.sh` (perfil `obs`). Grafana solo escucha en el propio servidor; desde tu equipo:

```bash
ssh -L 3030:127.0.0.1:3030 usuario@servidor
```

y abre `http://localhost:3030` con el usuario `admin` y `GRAFANA_PASSWORD`. Esa contraseña solo se aplica en el primer arranque; después se cambia desde Grafana.

Loki conserva los logs 30 días (`observability/loki.yaml`). Como pueden contener datos personales, el plazo debe fijarlo el responsable de protección de datos del organismo.

### Copias de seguridad

Guarda periódicamente, en un lugar protegido:

- **La base de datos** (volumen `sensia_backend-db`):
```bash
  docker compose stop backend-app
  docker run --rm -v sensia_backend-db:/datos:ro -v /opt/sensia/backups:/copia alpine \
    tar czf /copia/backend-db-$(date +%F).tar.gz -C /datos .
  docker compose start backend-app
```
- **`/opt/sensia/pki/ca`**: claves de las CA. Sin ellas hay que reemitir todos los certificados.
- **`/opt/sensia/pki/clientes`**: certificados entregados a los integradores.
- **`/opt/sensia/pki/publico`**, si el certificado es del organismo (el resto lo regenera `env.sh`).
- **`.env`**: contiene todos los secretos.

### Actualización

```bash
cd /opt/sensia/sensia.git
# 1. Copia de seguridad (ver arriba)
git pull
docker compose build
docker compose up -d
```

En producción la base de datos no modifica su esquema al arrancar. Si una versión nueva lo cambia, después de la copia de seguridad añade temporalmente `DB_SYNC_ALTER: "true"` al entorno de `backend-app`, ejecuta `docker compose up -d`, comprueba los logs y quítalo de nuevo.

### Cambiar la configuración

Vuelve a ejecutar `./env.sh` y aplica con `docker compose up -d --force-recreate`.
`env.sh` regenera certificados y almacenes aunque el `.env` no cambie, y `up -d` sin
`--force-recreate` no los recarga. `docker compose restart` no relee el `.env`.

### Renovación manual de certificados

SensIA no renueva ningún certificado de forma automática: su renovación corresponde al organismo.

- Certificado del organismo: deja el nuevo PEM (con la cadena) y su clave en `pki/publico`, ejecuta `./env.sh`, elige la opción 2 y aplica como arriba.
- Let's Encrypt: ejecuta a mano `./certbot.sh renovar` con el usuario que hizo la instalación (propietario de `/opt/sensia/pki`). Solo emite un certificado nuevo si el vigente está próximo a caducar y, en ese caso, regenera el almacén de Jetty, recarga nginx y reinicia `backend-ws`; no hay que aplicar nada más. Let's Encrypt no avisa por correo de la caducidad.
- Autofirmado e internos (825 días): `./env.sh` los regenera en cada ejecución.
- Cliente mTLS (365 días): `./env.sh`, responde «no» a reutilizarlo y entrega el nuevo `.p12`.
- Aplicar los cambios hechos con `./env.sh`:
  - docker compose up -d --force-recreate
  - docker compose ps        # todos deben pasar a healthy (backend-ws tarda unos 90 s)

---

## Seguridad

- Una sola entrada (nginx) y siempre HTTPS: TLS 1.2/1.3, cabeceras de seguridad, rechazo de nombres de dominio desconocidos.
- SOAP con token o con certificado de cliente validado por Jetty contra una lista de huellas.
- Comunicación gRPC entre `backend-ws` y `backend-ner` con mTLS.
- Contenedores sin root, con `no-new-privileges` y sin capacidades innecesarias; límites de memoria.
- Secretos en `.env` (permisos 640, grupo de administración); claves privadas en `pki`, un directorio por servicio, con permisos mínimos.
- Grafana y las herramientas de observabilidad no se publican; Alloy accede a Docker a través de un proxy de solo lectura.
- Registro de datos sensibles desactivado por defecto.

---

## Referencia de configuración

**Origen:** `.env` = lo genera `env.sh` · fija = en `docker-compose.yml` · imagen = en el Dockerfile ·
código = valor por defecto del servicio · .env.build = `backend-ner/scripts/.env.build` (solo al construir).

### Docker Compose

| Variable | Origen | Uso |
|---|---|---|
| `COMPOSE_PROFILES` | .env | `obs` activa la observabilidad |
| `PORT_PROXY_HTTP`, `PORT_PROXY_HTTPS`, `PORT_WS_MTLS` | .env | Puertos que publica nginx (80, 443, 8443). Sin mTLS, el 8443 se publica solo en `127.0.0.1` |
| `CERTS_GID` | .env | Grupo con acceso de lectura a `pki/publico`, `pki/ws`, `pki/ner` y `pki/app` |
| `REQUIRE_MTLS` | .env | Activa la vía con certificado: el conector 8443 de Jetty (`JETTY_SSL_ENABLED`). `env.sh` genera además el certificado de cliente y la lista de huellas |

### proxy-nginx

| Variable | Origen | Uso |
|---|---|---|
| `DOMAIN_NAME` | .env | Dominio servido; nombre TLS hacia Jetty |
| `CERT_FILE`, `KEY_FILE` | .env | Certificado y clave del dominio en `pki/publico` |

### frontend-app

| Variable | Origen | Uso |
|---|---|---|
| `API_URL` | fija (`/api`) | URL base de la API (mismo origen) |
| `ENABLE_TRACING` | .env | Propaga el contexto de trazas en las llamadas a la API |

### backend-app

| Variable | Origen | Uso |
|---|---|---|
| `PORT` | fija (`3000`) | Puerto interno |
| `LOG_LEVEL` | .env | Nivel de log |
| `MAX_BODY_SIZE` | fija (`70mb`) | Tamaño máximo de petición |
| `DB_STORAGE`, `DIR_LOGS` | fijas | Base de datos y logs (en volúmenes) |
| `HARD_MAINTENANCE` | .env | Modo mantenimiento |
| `SOAP_API_URL` | fija | `https://${DOMAIN_NAME}/services/SensiaService` (alias interno de `backend-ws`) |
| `SOAP_API_TOKEN` | .env | Token del servicio SOAP |
| `NODE_EXTRA_CA_CERTS` | fija | CA interna, para confiar en el certificado autofirmado |
| `JWT_SECRET`, `ALTCHA_HMAC_KEY` | .env | Secretos de sesión y captcha |
| `DEFAULT_ADMIN_USER`, `DEFAULT_ADMIN_PASSWORD` | .env | Administrador inicial |
| `SMTP_AUTH_METHOD`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_SECURE`, `SMTP_USER`, `SMTP_PASS`, `EMAIL_FROM` | .env | Correo saliente |
| `OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET`, `OAUTH_REFRESH_TOKEN`, `OAUTH_TOKEN_URL`, `OAUTH_SCOPE` | .env | Correo con OAuth2 |
| `OTEL_SDK_DISABLED`, `OTEL_TRACES_EXPORTER` | .env | Activación de trazas |
| `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_*` | fijas | Destino de trazas (Tempo) |

No se definen en producción: `CORS_ORIGIN` (solo desarrollo) y `DB_SYNC_ALTER` (solo para actualizaciones con cambio de esquema).

### backend-ws

| Variable | Origen | Uso |
|---|---|---|
| `AUTH_TOKEN` | .env (`SOAP_API_TOKEN`) | Token SOAP esperado |
| `AUTH_CERT_WHITELIST` | .env | Huellas SHA-256 autorizadas, separadas por `;` |
| `JETTY_HTTP_ENABLED` | fija (`false`) | HTTP desactivado (solo desarrollo) |
| `JETTY_HTTPS_ENABLED`, `JETTY_HTTPS_PORT` | fijas (`true`, `443`) | HTTPS con token |
| `JETTY_SSL_ENABLED` | .env (`REQUIRE_MTLS`) | Activa el conector con certificado obligatorio |
| `JETTY_SSL_PORT`, `JETTY_SSL_REQUIRECLIENTAUTH` | fijas (`8443`, `true`) | Puerto y certificado de cliente obligatorio |
| `JETTY_SSL_KEYSTORE_PATH`, `JETTY_SSL_TRUSTSTORE_PATH` | fijas | Almacenes en `pki/ws` |
| `JETTY_SSL_KEYSTORE_PASSWORD`, `JETTY_SSL_TRUSTSTORE_PASSWORD` | .env | Contraseñas de los almacenes |
| `JETTY_SSL_VALIDATEPEERCERTS`, `JETTY_SSL_ENABLECRLDP`, `JETTY_SSL_ENABLEOCSP` | fijas (`false`) | Sin CRL/OCSP: se revoca quitando la huella |
| `JETTY_HTTP_MAXPOSTSIZE` | fija (`73400320`) | Tamaño máximo de petición (bytes) |
| `JETTY_QOS_MAXREQUESTS`, `JETTY_QOS_SUSPENDMS` | fijas (`8`, `30000`) | Documentos simultáneos y espera en cola |
| `JETTY_THREADPOOL_*`, `JETTY_CONNECTOR_IDLETIMEOUTMS` | fijas | Hilos y tiempos de inactividad |
| `GRPC_TARGET_HOST`, `GRPC_TARGET_PORT`, `GRPC_TIMEOUT_SECONDS` | fijas (`backend-ner`, `50051`, `10`) | Conexión con `backend-ner` |
| `GRPC_TLS_ENABLED` | .env (`ENFORCE_SECURITY`) | mTLS hacia `backend-ner` |
| `GRPC_TLS_CA`, `GRPC_TLS_CERT`, `GRPC_TLS_KEY` | fijas | Certificados gRPC del cliente |
| `SENSIA_LOG_LEVEL` | .env (`LOG_LEVEL`) | Nivel de log |
| `SENSIA_LOG_SENSIBLE`, `SENSIA_LOG_SENSIBLE_DIR` | fijas (`OFF`) | Registro de datos sensibles |
| `PROCESSING_*` | opcionales | Parámetros de procesamiento (ver «Operación») |
| `OTEL_*` | .env + fijas | Trazas |

### backend-ner

| Variable | Origen | Uso |
|---|---|---|
| `ENV` | .env (`production`) | `development` desactiva el mTLS |
| `LOG_LEVEL` | .env | Nivel de log |
| `GRPC_BIND_TYPE`, `GRPC_HOST`, `GRPC_PORT` | fijas (`TCP`, `0.0.0.0`, `50051`) | Escucha gRPC |
| `ENFORCE_SECURITY` | .env | mTLS en el servidor gRPC |
| `TLS_CA_FILE`, `TLS_CERT_FILE`, `TLS_KEY_FILE` | fijas | Certificados gRPC del servidor |
| `MODEL_PATH`, `TOKENIZER_PATH` | código (`models/…`) | Modelo y tokenizador; deben coincidir con `.env.build` |
| `ONNX_EXECUTION_PROVIDER`, `NER_MERGE_ORG_LOC`, `NER_AGGREGATION` | código | Ejecución y postproceso del NER |
| `HF_HUB_OFFLINE`, `TRANSFORMERS_OFFLINE`, `HF_HUB_DISABLE_TELEMETRY` | fijas (`1`) | Sin acceso a Hugging Face en ejecución |
| `OTEL_*` | .env + fijas | Trazas |

### backend-ner: construcción del modelo

Solo se usan al construir la imagen (o con `make dev-model`); el servicio en ejecución no las lee.

| Variable | Origen | Uso |
|---|---|---|
| `MODEL_NAME` | .env.build | Modelo de Hugging Face que se exporta a ONNX |
| `MODEL_REVISION` | .env.build | Commit fijado, para que la construcción sea reproducible |
| `MODEL_PATH`, `TOKENIZER_PATH` | .env.build | Dónde se escriben; deben coincidir con los del servicio |

### Observabilidad

| Servicio | Variable | Origen | Uso |
|---|---|---|---|
| `grafana` | `GRAFANA_PASSWORD` | .env | Contraseña inicial del administrador |
| `grafana` | `GF_*` | fijas | Sin registro, sin acceso anónimo, sin telemetría |
| `docker-proxy` | `CONTAINERS` | fija (`1`) | Solo lectura de contenedores y logs |

---

## Desarrollo y pruebas

Cada componente se puede compilar y probar por separado. Ejecuta make en cada proyecto. Consulta los ficheros:

- `backend-ws/Makefile`
- `backend-ner/Makefile`
- `backend-app/Makefile`
- `frontend-app/Makefile`

---

## Stack tecnológico

- **Frontend:** Angular 21, servido con Caddy
- **Backend app:** Node.js 24
- **Servicio SOAP:** Java 21, Jetty 12, Apache CXF, OpenCV 4.13, Tesseract 4
- **NER:** Python 3.12, ONNX Runtime, modelo `BSC-LT/roberta_model_for_anonimization`
- **Proxy:** nginx 1.30
- **Observabilidad:** Grafana, Loki, Tempo, Alloy
- **Despliegue:** Docker y Docker Compose

---

## Licencia

Este proyecto se distribuye bajo licencia **EUPL v1.2** (European Union Public License).