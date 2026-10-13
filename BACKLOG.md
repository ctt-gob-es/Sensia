# Backlog del Proyecto SensIA

## Bugs & Hotfixes (Prioridad Alta)

* [x] **[FIX-001]** Corregir propagación de errores al usuario: la API devuelve (ej. PDF encriptado).

* [ ] **[FIX-002]** Solucionar el problema de reconocimiento que fusiona elementos distintos situados en la misma línea en una única entidad (ej. detecta Municipio + Nombre de persona como un solo "PERSON NAME").

* [ ] **[FIX-003]** Ajustar el grosor del trazado de anonimización para que se dimensione automaticamente según el interlineado y evite tapar parte del párrafo superior e inferior en textos muy juntos.

* [ ] **[FIX-004]** Mejorar el reconocimiento facial para que detecte rostros en documentos oficiales (como el DNI) que tienen marcas de agua superpuestas.

* [ ] **[FIX-005]** Ampliar el reconocimiento de direcciones para que anonimice también los números de policía, piso y otros detalles complementarios, no solo el nombre de la calle.

* [x] **[FIX-006]** Logs sin texto de documentos: logger dedicado `sensia.sensible` (OFF por defecto, en un fichero aparte).

* [ ] **[FIX-007]** OCR en PDFs mixtos: la decisión de aplicar OCR se toma solo con la primera página, por lo que las páginas escaneadas posteriores pueden quedar sin analizar (riesgo de fuga). Decidir OCR página a página.
* [ ] **[FIX-008]** Añadir `nameConstraints` a la CA interna en `env.sh` (`crear_ca`/`paso_ca`), limitada a `DOMAIN_NAME`, `backend-ner`, `localhost` y `127.0.0.1`. Hay que probar web + SOAP + gRPC con una PKI nueva. Hasta entonces, el modo autofirmado (opción 1) solo se usa en Pruebas o Intranet.

* [ ] **[FIX-009]** Rendimiento Memoria. Documentos en disco en lugar de en memoria. En encolarTrabajo, escribir el byte[] a un temporal y pasar el File a la tarea; en consultarYBorrar, servir la salida desde el fichero. La entrada en streaming exige MTOM, lo que cambia el contrato (WSDL, PL/SQL y backend-app). Revisar también redactImage y extractImages, y borrar los job_*.tmp huérfanos al arrancar.

* [ ] **[FIX-010]** Seguridad SOAP: reforzar la validación de las peticiones antes de procesarlas.

* [ ] **[FIX-011]** Seguridad de imágenes: limitar el tamaño de las imágenes al decodificarlas.

* [ ] **[FIX-012]** Seguridad SOAP: asociar cada ticket al `cliente` que lo creó y rechazar en `getStatus` la consulta de otro cliente (es riesgo muy bajo, el `ticketId`, un UUID aleatorio y largo).

* [ ] **[FIX-013]** Visor PDF: los iconos de las anotaciones de tipo nota se descargan de `unpkg.com` (`imageResourcesPath` de ng2-pdf-viewer, sin opción para cambiarlo). Bloquearlos con una Content-Security-Policy en nginx o servirlos desde la propia imagen.

## Funcionalidades (Evolutivo - `develop`)

* [ ] **[FEAT-001]** Integrar soporte para detección con YOLO / YOLOX (yolo+ort): matrículas, carteles, personas y vehículos en las imagenes.

* [x] **[FEAT-002]** Habilitar una opción para censurar de forma iteractiva por coordenadas zonas o campos.

* [ ] **[FEAT-003]** Añadir un indicador en la lista de la pantalla principal que muestre el número de datos tratados ('0' o 'n') por cada documento.

* [ ] **[FEAT-004]** NER: Tipos de vía configurables en un fichero común (tipos_via.txt) para backend-ner y las regex de patrones.txt, con soporte para otros idiomas (gallego, euskera, con el tipo de vía detrás del nombre).

* [ ] **[FEAT-005]** Predefinir y compartir plantillas por tipos de documentos. Carga de plantillas predefinidas al crear los usuarios.

* [ ] **[FEAT-006]** Devolver la imagen censurada a su resolución original. Reescalar los rectángulos de detectFaces con el factor inverso y aplicar la censura sobre el Mat original, escalando el kernel de desenfoque o pixelado proporcionalmente. Probar con imágenes de 4000 px o más que las caras no son reconocibles.

* [ ] **[FEAT-007]** Validar el realce de contraste en la detección de rostros (`processing.face.enhanceContrast`, desactivado). Medir con un conjunto propio (fotos oscuras, a contraluz, fotocopias de documentos de identidad) los rostros detectados y los falsos positivos con y sin la segunda pasada, y el tiempo por imagen. Si mejora la detección sin muchos falsos positivos, activarlo por defecto y documentarlo.

* [ ] **[FEAT-008]** Rostros girados: YuNet detecta caras aproximadamente derechas. Si no se detecta ninguna, repetir la detección con la imagen girada 90°, 180° y 270° (por ejemplo, documentos de identidad escaneados de lado).

* [ ] **[FEAT-009]** Rostros pequeños en imágenes grandes: la detección se hace sobre la imagen reducida a 1024 px y en fotos de grupo las caras pequeñas quedan por debajo del tamaño mínimo de YuNet. Detectar por trozos o a mayor resolución (relacionado con FEAT-006).


## Técnica & Refactorización

* [ ] **[TECH-001]** Mejorar la Developer Experience (DevEx): documentar y facilitar el acceso a utilidades auxiliares en desarrollo (correo de test, herramientas de observabilidad).

* [ ] **[TECH-002]** NER por página o bloque (Pendiente: Incluye trocear textos de más de 512 tokens y las palabras que PDFBox parte en varios fragmentos).

* [ ] **[TECH-003]** Contexto OTel y ticketId en los hilos del pool (Pendiente).

* [ ] **[TECH-004]** Build: versiones de plugins fijadas y tests en CI (Pendiente nuevo: Surefire 3.5.2, resources; workflow en .github).

* [ ] **[TECH-005]** Volver a publicar la integración Oracle PL/SQL (`backend-ws/integration/oracle-plsql`). Retirada de `main` en la primera versión publicada; hasta repetir tests. Se mantiene en una rama interna `features/plsql`, no publicada.

* [ ] **[TECH-006]** Retirar el pool de Tesseract sin uso (`OcrResourcesManager`, `TesseractInstanceFactory`): `convertirAPdfBuscable` crea su propia instancia.

## Despliegue (env.sh / docker-compose)

* [ ] **[DEP-001]** env.sh: mejorar gestión de certificados para tareas de renovación e inclusión de nuevos clientes


## Documentación

* [ ] **[DOC-001]** Pendiente explicar en los manuales qué cubren las categorías NER.



## Planificación de Release v1.0.0

* [x] **[REL-001]** Generar rama aplanada `ctt/main`.

* [x] **[REL-002]** Sustituir los marcadores de las cabeceras de licencia por la URL real del repositorio.

* [x] **[REL-003]** El piloto se instaló con env.sh y los valores por defecto: revisar ALTCHA_HMAC_KEY, la contraseña de Grafana y los puertos expuestos.
