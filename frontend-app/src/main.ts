/*
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE en la raíz del proyecto para ver los 
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */


import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
// Worker de pdf.js servido por la propia aplicación; si no se indica, ng2-pdf-viewer lo descarga de un CDN
(window as any).pdfWorkerSrc = '/pdf.worker.min.mjs';

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));