# Avisos legales y software de terceros

El proyecto **SensIA** ha sido posible gracias al uso de excelentes herramientas y librerías de código abierto. En cumplimiento de sus respectivas licencias, se reconocen a continuación los derechos de autor de las tecnologías integradas en este software.

Todo el mérito de estas librerías corresponde a sus respectivos creadores y comunidades de desarrollo.

### Ecosistema Frontend y Backend
* **Angular** ([https://github.com/angular/angular](https://github.com/angular/angular))
  * Licencia: MIT License
  * Copyright (c) Google LLC.
* **Node.js** ([https://nodejs.org](https://nodejs.org/))
  * Licencia: MIT License
  * Copyright (c) OpenJS Foundation and Node.js contributors.
* **PDF.js** ([https://github.com/mozilla/pdf.js](https://github.com/mozilla/pdf.js))
  * Licencia: Apache License 2.0
  * Copyright 2024 Mozilla Foundation.
  * La aplicación web incluye su worker (`pdf.worker.min.mjs`) y sus CMaps, servidos desde la propia imagen.
* **ng2-pdf-viewer** ([https://github.com/VadimDez/ng2-pdf-viewer](https://github.com/VadimDez/ng2-pdf-viewer))
  * Licencia: MIT License
  * Copyright (c) Vadym Yatsyuk.
* **ALTCHA** (`altcha` y `altcha-lib`) ([https://github.com/altcha-org/altcha](https://github.com/altcha-org/altcha))
  * Licencia: MIT License
  * Copyright (c) 2023 Daniel Regeci, BAU Software s.r.o.
* **Roboto** ([https://github.com/googlefonts/roboto-classic](https://github.com/googlefonts/roboto-classic)), distribuida mediante `@fontsource/roboto`
  * Licencia: SIL Open Font License 1.1
  * Copyright 2011 The Roboto Project Authors.

### Ecosistema Web Services (Java)
* **Apache PDFBox** ([https://pdfbox.apache.org](https://pdfbox.apache.org/))
  * Licencia: Apache License 2.0
  * Copyright (c) The Apache Software Foundation.
* **Apache CXF (SOAP)** ([https://cxf.apache.org](https://cxf.apache.org/))
  * Licencia: Apache License 2.0
  * Copyright (c) The Apache Software Foundation.
* **Eclipse Jetty** ([https://eclipse.dev/jetty](https://eclipse.dev/jetty/))
  * Licencia: Apache License 2.0 / Eclipse Public License 2.0
  * Copyright (c) Eclipse Foundation.

### Visión Artificial, OCR y Procesamiento de Imágenes
* **OpenCV** ([https://opencv.org](https://opencv.org/))
  * Licencia: Apache License 2.0
  * Copyright (c) OpenCV Foundation.
* **Tesseract OCR** ([https://github.com/tesseract-ocr/tesseract](https://github.com/tesseract-ocr/tesseract))
  * Licencia: Apache License 2.0
  * Copyright (c) Google LLC / Ray Smith / Tesseract contributors.
* **YuNet (Face Detection)** ([https://github.com/ShiqiYu/libfacedetection](https://github.com/ShiqiYu/libfacedetection))
  * Licencia: MIT License
  * Copyright (c) Shiqi Yu.
* **ZXing ("Zebra Crossing" - QR/Barcode)** ([https://github.com/zxing/zxing](https://github.com/zxing/zxing))
  * Licencia: Apache License 2.0
  * Copyright (c) ZXing Authors.

### Reconocimiento de entidades (backend-ner)
* **Modelo BSC-LT/roberta_model_for_anonimization** ([https://huggingface.co/BSC-LT/roberta_model_for_anonimization](https://huggingface.co/BSC-LT/roberta_model_for_anonimization))
  * Licencia: MIT License
  * Autor: Barcelona Supercomputing Center (BSC-CNS).
  * Se descarga en la revisión fijada en `backend-ner/scripts/.env.build` y se exporta a ONNX al construir la imagen; no se incluye en el repositorio.
* **ONNX Runtime** ([https://github.com/microsoft/onnxruntime](https://github.com/microsoft/onnxruntime))
  * Licencia: MIT License
  * Copyright (c) Microsoft Corporation.
* **Hugging Face Transformers** ([https://github.com/huggingface/transformers](https://github.com/huggingface/transformers))
  * Licencia: Apache License 2.0
  * Copyright (c) The Hugging Face Team.
* **gRPC** ([https://grpc.io](https://grpc.io/)) y **Protocol Buffers** ([https://github.com/protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf))
  * Licencias: Apache License 2.0 (gRPC) y BSD 3-Clause (Protocol Buffers)
  * Copyright (c) The gRPC Authors / Google LLC.

### Datos incluidos en el repositorio
* **Tesseract tessdata** (`spa`, `eng`, `osd`) ([https://github.com/tesseract-ocr/tessdata](https://github.com/tesseract-ocr/tessdata)) y la fuente `pdf.ttf` del repositorio de Tesseract.
  * Licencia: Apache License 2.0
  * Copyright (c) Google LLC / Tesseract contributors.
  
### Despliegue e Infraestructura
* **Caddy Web Server** ([https://caddyserver.com](https://caddyserver.com/))
  * Licencia: Apache License 2.0
  * Copyright (c) Light Code Labs.
* **NGINX Open Source** (https://github.com/nginx/nginx)
  * Licencia: BSD simplificada de 2 cláusulas (BSD 2-Clause).
  * Copyright (C) 2002-2021 Igor Sysoev.
  * Copyright (C) 2011-2026 Nginx, Inc.
  * NGINX se utiliza como servidor web y proxy inverso dentro de la infraestructura de despliegue de SensIA.

---

SensIA es software de Suma Gestión Tributaria, organismo autónomo de la Diputación de Alicante ([www.suma.es](https://www.suma.es)), distribuido bajo la licencia EUPL-1.2. Incorpora y utiliza los componentes listados anteriormente.

---
*Para consultar los términos exactos de las licencias Apache 2.0 y MIT, por favor visite [http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0) y [https://opensource.org/licenses/MIT](https://opensource.org/licenses/MIT) respectivamente.*
