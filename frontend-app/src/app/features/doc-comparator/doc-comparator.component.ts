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

import {
  AfterViewInit, Component, ElementRef, EventEmitter, HostListener, Input, NgZone,
  OnChanges, OnDestroy, Output, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PdfViewerModule } from 'ng2-pdf-viewer';

type Panel = 'A' | 'B';

const ZOOM_MIN = 0.5;
const ZOOM_MAX = 3;
const PASO_ZOOM = 0.2;

@Component({
  selector: 'app-doc-comparator',
  standalone: true,
  imports: [CommonModule, PdfViewerModule],
  templateUrl: './doc-comparator.component.html',
  styleUrls: ['./doc-comparator.component.css']
})
export class DocComparatorComponent implements OnChanges, AfterViewInit, OnDestroy {
  private ngZone = inject(NgZone);

  @Input('originalSrc') originalRaw: string | null | undefined = null;
  @Input('redactedSrc') redactedRaw: string | null | undefined = null;
  @Input() mimeType = 'application/pdf';
  @Input() nombre = '';
  @Input() definitivo = false;
  @Output() close = new EventEmitter<void>();

  @ViewChild('pdfViewerA') pdfViewerA?: any;
  @ViewChild('pdfViewerB') pdfViewerB?: any;
  @ViewChild('imageContainerA') imageContainerA?: ElementRef<HTMLElement>;
  @ViewChild('imageContainerB') imageContainerB?: ElementRef<HTMLElement>;
  @ViewChild('btnVolver') btnVolver?: ElementRef<HTMLButtonElement>;

  readonly ZOOM_MIN = ZOOM_MIN;
  readonly ZOOM_MAX = ZOOM_MAX;

  zoom = 1;
  rotacion: Record<Panel, number> = { A: 0, B: 0 };
  paginas: Record<Panel, number> = { A: 0, B: 0 };
  sincronizado = true;
  isImage = false;

  pdfA: Uint8Array | null = null;
  pdfB: Uint8Array | null = null;
  imgA: string | null = null;
  imgB: string | null = null;

  private isSyncing = false;
  private scrollA?: HTMLElement;
  private scrollB?: HTMLElement;
  private syncHandlerA?: () => void;
  private syncHandlerB?: () => void;
  private temporizadorSync?: number;

  get hayA(): boolean { return this.isImage ? !!this.imgA : !!this.pdfA; }
  get hayB(): boolean { return this.isImage ? !!this.imgB : !!this.pdfB; }

  get paginasDistintas(): boolean {
    return !this.isImage && this.paginas.A > 0 && this.paginas.B > 0 && this.paginas.A !== this.paginas.B;
  }

  ngOnChanges(): void {
    // Con cada cambio de entradas: el componente puede recibir otro documento sin destruirse
    this.removeListeners();
    this.isImage = (this.mimeType || '').startsWith('image/');
    this.paginas = { A: 0, B: 0 };
    this.imgA = this.isImage ? this.aDataUrl(this.originalRaw) : null;
    this.imgB = this.isImage ? this.aDataUrl(this.redactedRaw) : null;
    this.pdfA = this.isImage ? null : this.aBytes(this.originalRaw);
    this.pdfB = this.isImage ? null : this.aBytes(this.redactedRaw);
  }

  ngAfterViewInit(): void {
    this.btnVolver?.nativeElement.focus();
  }

  ngOnDestroy(): void {
    window.clearTimeout(this.temporizadorSync);
    this.removeListeners();
  }

  @HostListener('document:keydown.escape')
  onClose(): void {
    this.close.emit();
  }

  cambiarZoom(sentido: 1 | -1): void {
    const nuevo = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, this.zoom + sentido * PASO_ZOOM));
    this.zoom = Math.round(nuevo * 10) / 10;
  }

  restablecerZoom(): void {
    this.zoom = 1;
  }

  rotar(panel: Panel, sentido: 1 | -1): void {
    this.rotacion[panel] = (this.rotacion[panel] + sentido * 90 + 360) % 360;
  }

  rotarAmbos(): void {
    this.rotar('A', 1);
    this.rotar('B', 1);
  }

  alCargarPdf(panel: Panel, pdf: { numPages: number }): void {
    this.paginas[panel] = pdf.numPages;
    this.programarSync();
  }

  alCargarImagen(): void {
    this.programarSync();
  }

  alternarSincronizacion(): void {
    this.sincronizado = !this.sincronizado;
    // Al activarla, el anonimizado se coloca donde está el original
    if (this.sincronizado && this.scrollA && this.scrollB) this.seguir(this.scrollA, this.scrollB);
  }

  private programarSync(): void {
    window.clearTimeout(this.temporizadorSync);
    this.temporizadorSync = window.setTimeout(() => this.setupSync(), 100);
  }

  private setupSync(): void {
    this.removeListeners();
    const a: HTMLElement | undefined = this.isImage
      ? this.imageContainerA?.nativeElement
      : this.pdfViewerA?.pdfViewerContainer?.nativeElement;
    const b: HTMLElement | undefined = this.isImage
      ? this.imageContainerB?.nativeElement
      : this.pdfViewerB?.pdfViewerContainer?.nativeElement;
    if (!a || !b) return;

    this.scrollA = a;
    this.scrollB = b;
    this.syncHandlerA = () => this.seguir(a, b);
    this.syncHandlerB = () => this.seguir(b, a);
    // Fuera de la zona: el scroll no necesita detección de cambios
    this.ngZone.runOutsideAngular(() => {
      a.addEventListener('scroll', this.syncHandlerA!, { passive: true });
      b.addEventListener('scroll', this.syncHandlerB!, { passive: true });
    });
  }

  private seguir(origen: HTMLElement, destino: HTMLElement): void {
    if (!this.sincronizado || this.isSyncing) return;
    this.isSyncing = true;
    destino.scrollTop = this.proporcion(origen.scrollTop, origen.scrollHeight - origen.clientHeight)
      * (destino.scrollHeight - destino.clientHeight);
    destino.scrollLeft = this.proporcion(origen.scrollLeft, origen.scrollWidth - origen.clientWidth)
      * (destino.scrollWidth - destino.clientWidth);
    requestAnimationFrame(() => (this.isSyncing = false));
  }

  private proporcion(posicion: number, maximo: number): number {
    return maximo > 0 ? posicion / maximo : 0;
  }

  private removeListeners(): void {
    if (this.scrollA && this.syncHandlerA) this.scrollA.removeEventListener('scroll', this.syncHandlerA);
    if (this.scrollB && this.syncHandlerB) this.scrollB.removeEventListener('scroll', this.syncHandlerB);
    this.scrollA = this.scrollB = undefined;
    this.syncHandlerA = this.syncHandlerB = undefined;
  }

  private aDataUrl(base64: string | null | undefined): string | null {
    if (!base64) return null;
    return base64.startsWith('data:') ? base64 : `data:${this.mimeType};base64,${base64}`;
  }

  private aBytes(base64: string | null | undefined): Uint8Array | null {
    if (!base64) return null;
    const coma = base64.indexOf(',');
    const limpio = base64.startsWith('data:') && coma >= 0 ? base64.slice(coma + 1) : base64;
    try {
      const binario = atob(limpio);
      const bytes = new Uint8Array(binario.length);
      for (let i = 0; i < binario.length; i++) bytes[i] = binario.charCodeAt(i);
      return bytes;
    } catch (err) {
      console.error('No se pudo decodificar el documento', err);
      return null;
    }
  }
}