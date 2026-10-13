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

import { Component, ElementRef, afterNextRender, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AppConfigService } from '../../core/services/app-config.service';
import { DocumentService } from '../../core/services/document.service';
import { DocumentStateService } from '../../core/services/document-state.service';
import { DocumentProcessingService } from '../../core/services/document-processing.service';
import { DocComparatorComponent } from '../doc-comparator/doc-comparator.component';
import { DocState, DocumentItemUI, FaseServicio } from '../../core/interfaces/types';

interface PresentacionEstado {
  texto: string;
  clase: string;
}

/** Cómo se muestra cada estado en la bandeja (referencias constantes para la plantilla) */
const PROCESANDO: PresentacionEstado = { texto: 'Procesando…', clase: 'procesando' };
const PRESENTACION: Partial<Record<DocState, PresentacionEstado>> = {
  [DocState.REVIEW_READY]: { texto: 'Pendiente de revisión', clase: 'pendiente' },
  [DocState.COMPLETED]: { texto: 'Listo para descargar', clase: 'listo' },
  [DocState.ERROR]: { texto: 'Error', clase: 'error' },
};

/** Fase que devuelve getStatus mientras el documento está en el servidor */
const FASES_SERVICIO: Record<FaseServicio, PresentacionEstado> = {
  EN_COLA: { texto: 'En cola…', clase: 'procesando' },
  PROCESANDO: PROCESANDO,
};

// Id del documento marcado en la bandeja, fuera del componente para conservarlo al volver de otra pantalla
let marcado: string | null = null;

@Component({
  selector: 'app-doc-upload',
  standalone: true,
  imports: [CommonModule, DocComparatorComponent],
  templateUrl: './doc-upload.component.html',
  styleUrls: ['./doc-upload.component.css']
})


export class DocUploadComponent {
  public documentService = inject(DocumentService);
  public config = inject(AppConfigService);
  private procesamiento = inject(DocumentProcessingService);
  private docStateService = inject(DocumentStateService);
  private router = inject(Router);
  private host = inject<ElementRef<HTMLElement>>(ElementRef);
  public DocState = DocState;

  constructor() {
    // Al volver de otra pantalla, se lleva la vista al documento marcado
    afterNextRender(() => {
      this.host.nativeElement.querySelector('.document-card.marcado')
        ?.scrollIntoView({ block: 'center' });
    });
  }

  estaMarcado(doc: DocumentItemUI): boolean {
    return doc.id === marcado;
  }

  /** Marca o desmarca al pulsar la tarjeta; los botones y la miniatura tienen su propia acción */
  alternarMarca(doc: DocumentItemUI, event: MouseEvent): void {
    if ((event.target as HTMLElement).closest('.doc-actions, .doc-preview.clicable')) return;
    marcado = this.estaMarcado(doc) ? null : doc.id;
  }
  

  get enProceso(): boolean {
    return this.procesamiento.hayEnProceso();
  }

  async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const archivos = Array.from(input.files ?? []);
    input.value = ''; // permite volver a seleccionar los mismos archivos
    if (archivos.length === 0) return;

    // Las opciones salen de las preferencias vigentes del usuario, leídas en este momento
    const { error, rechazos } = await this.procesamiento.anadirArchivos(archivos);
    if (error) {
      alert(error);
    } else if (rechazos.length > 0) {
      alert('Algunos archivos no se han añadido:\n\n' + rechazos.join('\n'));
    }
  }

  // ---------------------------------------------------------------------------
  // Estado y acciones de cada documento
  // ---------------------------------------------------------------------------

  estado(doc: DocumentItemUI): PresentacionEstado {
    return PRESENTACION[doc.status]
      ?? (doc.estadoServicio ? FASES_SERVICIO[doc.estadoServicio] : PROCESANDO);
  }

  puedeRevisar(doc: DocumentItemUI): boolean {
    return !this.procesamiento.estaEnProceso(doc);
  }

  puedeDescargar(doc: DocumentItemUI): boolean {
    return doc.status === DocState.COMPLETED && !!doc.finalBase64;
  }

  /** Documento abierto en el comparador */
  comparando: DocumentItemUI | null = null;

  /** Mensaje del servicio al terminar bien (se muestra en verde) */
  mensajeFin(doc: DocumentItemUI): string {
    return this.terminado(doc) ? (doc.resultMessage ?? '') : '';
  }

  puedeComparar(doc: DocumentItemUI): boolean {
    return this.puedeDescargar(doc);
  }

  comparar(doc: DocumentItemUI): void {
    if (this.puedeComparar(doc)) this.comparando = doc;
  }

  private terminado(doc: DocumentItemUI): boolean {
    return doc.status === DocState.REVIEW_READY || doc.status === DocState.COMPLETED;
  }

  revisar(doc: DocumentItemUI): void {
    if (!this.puedeRevisar(doc)) return;
    marcado = doc.id;

    // Si la revisión anterior ya cambió el documento, se analiza de nuevo como al subirlo
    if (doc.modificadoEnRevision) {
      const mensaje = `Se descartarán los cambios de la revisión anterior de «${doc.file.name}» ` +
        `y el documento se volverá a analizar desde el principio.\n\n¿Desea continuar?`;
      if (!confirm(mensaje)) return;
      void this.procesamiento.reiniciar(doc);
    }

    this.docStateService.setDocumento(doc);
    this.router.navigate(['/revision']);
  }

  /** Miniatura: entra sin preguntar; si la revisión ya cambió el documento, solo para consultar */
  verRevision(doc: DocumentItemUI): void {
    if (!this.puedeRevisar(doc)) return;
    if (!doc.modificadoEnRevision || !doc.finalBase64) {
      this.revisar(doc);
      return;
    }
    marcado = doc.id;
    this.docStateService.setDocumento(doc, true);
    this.router.navigate(['/revision']);
  }


  descargar(doc: DocumentItemUI): void {
    if (!this.puedeDescargar(doc) || !doc.finalBase64) return;
    const mensaje = `⚠️ AVISO: DESCARGA DE DOCUMENTO ANONIMIZADO\n\n` +
      `Está a punto de descargar la versión anonimizada del documento: ` + doc.file.name + `\n` +
      `Al proceder, usted confirma que ha REVISADO y VALIDADO los criterios de protección aplicados.\n` +
      `¿DESEA CONTINUAR CON LA DESCARGA?`;

    if (!confirm(mensaje)) {
      return;
    }

    
    // Se usa Blob y no data: URL, porque Chrome/Edge no descargan data > 2 MB
    const binario = atob(doc.finalBase64);
    const bytes = new Uint8Array(binario.length);
    for (let i = 0; i < binario.length; i++) bytes[i] = binario.charCodeAt(i);
    const url = URL.createObjectURL(new Blob([bytes], { type: doc.file.type }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `CENSURADO_${doc.file.name}`;
    link.click();
    doc.descargado = true;
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  eliminar(doc: DocumentItemUI): void {
    if (doc.modificadoEnRevision && !doc.descargado &&
        !confirm(`«${doc.file.name}» tiene una revisión sin descargar. Si lo elimina, se perderá.\n\n¿Desea continuar?`)) {
      return;
    }
    this.procesamiento.descartar(doc);
  }

  vaciar(): void {
    const n = this.documentService.documents().length;
    const mensaje = `Se quitarán de la bandeja ${n === 1 ? 'el documento' : `los ${n} documentos`}. ` +
      `Se cancelará el procesamiento en curso y se perderán los resultados no descargados.\n\n¿Desea continuar?`;
    if (confirm(mensaje)) this.procesamiento.descartarTodos();
  }  
}