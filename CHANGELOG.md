# Registro de cambios

Todos los cambios relevantes de SensIA se documentan en este archivo.

El formato se basa en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y el proyecto sigue [Versionado Semántico](https://semver.org/lang/es/). A partir de la versión 1.0.0, cualquier cambio incompatible en el contrato del servicio SOAP (WSDL) implicará una nueva versión mayor.

## [1.0.0-rc.1] - 2026-10-09

Primera versión pública, candidata a la versión 1.0.0. Probada en un piloto en un organismo público y pendiente de despliegue generalizado.

### Añadido

**Motor de anonimización (backend-ws)**

- Servicio web SOAP (JAX-WS, Apache CXF) con procesamiento asíncrono por lotes (`submitBatch`, `getStatus`) y operaciones síncronas `redactImage` y `extractImages`.
- Detección de datos personales mediante expresiones regulares configurables: NIF, NIE, CIF, tarjetas bancarias, IBAN, teléfonos, correos electrónicos, direcciones, referencias catastrales, matrículas, bastidores y códigos seguros de verificación, entre otros. El patrón del número de afiliación a la Seguridad Social se incluye desactivado.
- Lista blanca con soporte de expresiones regulares (prefijo `regex:`) y comparación insensible a acentos, y lista negra de términos literales.
- Detección de nombres propios mediante diccionario.
- Detección y censura de códigos QR y códigos de barras.
- Detección y pixelado de rostros con YuNet, con umbral de confianza y filtros de tamaño y proporción configurables.
- Eliminación de imágenes incrustadas, con zonas de exclusión y protección del logotipo institucional.
- Censuras fijas por coordenadas.
- OCR previo con Tesseract para documentos sin capa de texto.
- Modo vista previa y confirmación de marcas revisadas, con verificación de integridad del documento de origen.
- Sanitización y rasterización del documento final para impedir la recuperación del contenido censurado.
- Autenticación mediante token SOAP y soporte de autenticación mutua TLS (mTLS).

**Reconocimiento de entidades (backend-ner, opcional)**

- Servicio gRPC de reconocimiento de entidades nombradas (personas, organizaciones, lugares y otras) basado en un modelo público del Barcelona Supercomputing Center (BSC-CNS), exportado a ONNX.
- Opciones por petición para activar cada tipo de entidad y ajustar el umbral de confianza.

**Aplicación web (frontend-app y backend-app)**

- Bandeja de trabajo con subida de documentos PDF e imágenes JPG y PNG, y seguimiento del estado de cada documento.
- Pantalla de revisión de marcas, con activación y desactivación individual de censuras.
- Comparador sincronizado entre el documento original y el censurado.
- Reprocesado de documentos con reglas específicas y plantillas reutilizables.
- Preferencias de procesamiento por usuario.
- Panel de administración con gestión de usuarios y controles globales: modo mantenimiento, autorregistro, recuperación de contraseña y captcha.
- Protección de los formularios de acceso con ALTCHA.
- Límite de tamaño de subida aplicado en frontend, backend de aplicación y motor.

**Despliegue y operación**

- Despliegue con Docker Compose y asistente de configuración (`env.sh`), que genera la PKI (certificados internos, del dominio y de cliente) y el fichero `.env`.
- Proxy inverso NGINX con TLS.
- Obtención del certificado de Let's Encrypt en la instalación (`certbot.sh`); su renovación es manual.
- Envío de correo mediante SMTP, con soporte de OAuth2 y relay opcional.
- Observabilidad basada en OpenTelemetry, con Grafana, Loki y Tempo.
- Makefile con comandos de desarrollo, pruebas, documentación y observabilidad.
- Manuales de usuario, integrador y técnico, con generación automática en PDF.
- Conjunto de documentos e imágenes para pruebas.
- Dependencias del motor actualizadas para corregir vulnerabilidades altas: Apache CXF 4.1.7, Eclipse Jetty 12.1.10, Bouncy Castle 1.85 y gRPC 1.75.0.
- Dependencias npm, Maven y Python analizadas con Trivy 0.75.0, sin vulnerabilidades altas ni críticas.


### Limitaciones conocidas

- Algunos códigos QR situados junto a bordes o líneas de tabla pueden no detectarse.
- Los datos repartidos en varias líneas dentro de una celda de tabla pueden no detectarse completos.
- En documentos escaneados de baja resolución, el OCR puede no reconocer todo el texto.
- El texto contenido en imágenes de baja resolución incrustadas en el PDF puede no anonimizarse.
- PDF mixtos (páginas nativas y escaneadas): la decisión de aplicar OCR se toma con la primera página.
- Los rostros girados, o muy pequeños en imágenes de gran tamaño, pueden no detectarse.
- El visor de PDF de la aplicación web descarga de `unpkg.com` los iconos de las anotaciones de tipo nota. La petición no envía el documento ni su texto.
- La aplicación web sirve desde su propia imagen las fuentes, el worker de pdf.js y sus tablas de caracteres, sin cargar recursos de terceros (salvo la excepción indicada en las limitaciones).
[1.0.0-rc.1]: https://github.com/ctt-gob-es/sensia/releases/tag/v1.0.0-rc.1
