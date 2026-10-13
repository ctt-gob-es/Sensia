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
  AfterViewInit, Component, ElementRef, HostListener, NgZone, OnInit, OnDestroy, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PdfViewerModule } from 'ng2-pdf-viewer';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { DocumentService } from '../../core/services/document.service';
import { DocumentStateService } from '../../core/services/document-state.service';
import { DocumentProcessingService } from '../../core/services/document-processing.service';
import { DocReprocessComponent, ResultadoRepetir } from '../doc-reprocess/doc-reprocess.component';
import { AjustesDocumento, contieneZona, mismaZona } from '../../core/utils/opciones-procesamiento';
import { DESCRIPCION_ZONA_MANUAL, TIPO_ZONA_FIJA } from '../../core/utils/marcas';
import {
  agruparApariciones,
  estaCensurada,
  fijarCensura,
  textoApariciones,
  textoTodas,
  textoZona,
  zonaDe
} from '../../core/utils/ocurrencias';
import {
  CapturaZonas,
  Pendiente,
  mismaFrase,
  motivoFraseNoValida,
  normalizarFrase,
  recortar
} from '../../core/utils/seleccion-visor';
import {
  Coordinate,
  DocState,
  DocumentItemUI,
  RedactionItem
} from '../../core/interfaces/types';

/** Milisegundos que se muestra un aviso del visor */
const DURACION_AVISO_MS = 5000;
/** Milisegundos que se muestra el aviso del panel de detecciones */
const DURACION_AVISO_PANEL_MS = 8000;

/** Nombre de cada tipo de detección en el panel */
const NOMBRES_TIPO: Record<string, string> = {
  FACE: 'Rostro',
  PERSON_NAME: 'Nombre',
  SENSITIVE_DATA: 'Dato sensible',
  QR_CODE: 'Código QR',
  NAMED_ENTITY: 'Entidad',
  FIXED_ZONE: 'Censura fija',
  IMAGE: 'Imagen'
};

/** Lo que el panel muestra de cada detección además de sus datos; se calcula al cargar */
interface InfoDeteccion {
  /** Apariciones del mismo elemento en el documento, incluida esta */
  grupo: RedactionItem[];
  /** «En 5 páginas», «3 veces» o vacío */
  apariciones: string;
  /** Nemotécnico de una censura fija: Z1, Z2… (su posición en «Censuras fijas») */
  zona?: string;
  /** Explicación del nemotécnico, para el tooltip */
  tituloZona?: string;
  /** Coordenadas «x, y, w, h» de una censura fija */
  coordenadas?: string;
}

/** Aviso breve del panel, con una acción opcional */
interface AvisoPanel {
  texto: string;
  accion?: string;
  ejecutar?: () => void;
}

@Component({
  selector: 'app-doc-revision',
  standalone: true,
  imports: [
    CommonModule,
    PdfViewerModule,
    DocReprocessComponent
  ],
  templateUrl: './doc-revision.component.html',
  styleUrls: ['./doc-revision.component.css']
})
export class DocRevisionComponent implements OnInit, AfterViewInit, OnDestroy {

  private router = inject(Router);
  private ngZone = inject(NgZone);
  private documentService = inject(DocumentService);
  private docStateService = inject(DocumentStateService);
  private procesamiento = inject(DocumentProcessingService);
  private finSub?: Subscription;

  /** Contenedor del visor: la selección de texto y la captura de zonas solo valen dentro de él */
  @ViewChild('visor') visor?: ElementRef<HTMLElement>;

  public DocState = DocState;
  readonly estaCensurada = estaCensurada;
  /** Documento de la bandeja: el procesamiento lo actualiza directamente */
  doc!: DocumentItemUI;
  /** Abierta desde la miniatura de un documento ya revisado: solo para consultar */
  soloLectura = false;

  // Visor
  currentPage = 1;
  totalPages = 1;
  paginasListas = false;      // número de páginas conocido (en PDF, cuando el visor lo ha cargado)
  zoom = 1.0;
  verOriginal = false;
  esPdf = true;
  pdfSource: any;

  // Detecciones: copia de trabajo. Los interruptores no llegan al documento
  // hasta que se aplican con «Actualizar vista previa» o «Confirmar».
  todasDetecciones: RedactionItem[] = [];
  deteccionesPagina: RedactionItem[] = [];
  hayCambios = false;         // marcas cambiadas que la vista previa aún no refleja
  /** Aviso del panel de detecciones, con su acción («Desactivar en todas», «Deshacer») */
  avisoPanel: AvisoPanel | null = null;

  /** Ajustes del documento mientras el diálogo «Repetir análisis» está abierto */
  ajustesEnEdicion: AjustesDocumento | null = null;
  /** Lo que muestra el diálogo al abrirse: los ajustes más los pendientes del visor, si los hay */
  ajustesIniciales?: AjustesDocumento;

  // Selección interactiva en el visor
  modoZona = false;
  /** Cambios hechos en el visor que aún no se han aplicado */
  pendientes: Pendiente[] = [];
  /** Selección de texto vigente, con la posición del botón flotante */
  seleccion: { texto: string; x: number; y: number } | null = null;
  mensaje = '';

  private pdfDoc: any;        // documento de pdf.js que entrega el visor al cargar
  private captura?: CapturaZonas;
  private info = new Map<RedactionItem, InfoDeteccion>();
  private arrastrandoRaton = false;
  private temporizadorSeleccion?: number;
  private temporizadorMensaje?: number;
  private temporizadorAvisoPanel?: number;
  private quitarEscuchas: (() => void)[] = [];

  get procesando(): boolean {
    return this.procesamiento.estaEnProceso(this.doc);
  }

  get tieneResultado(): boolean {
    return !!this.doc.finalBase64;
  }

  get mostrandoOriginal(): boolean {
    return this.verOriginal || !this.tieneResultado;
  }

  get hayPendientes(): boolean {
    return this.pendientes.length > 0;
  }

  /** «Repetir análisis» siempre está disponible mientras su diálogo no esté abierto */
  get puedeReprocesar(): boolean {
    return !this.ajustesEnEdicion;
  }

  get puedeAplicar(): boolean {
    return !this.procesando && this.hayPendientes;
  }

  get puedeActualizar(): boolean {
    return !this.procesando && this.tieneResultado && this.hayCambios && !this.hayPendientes;
  }

  get puedeConfirmar(): boolean {
    // Un documento ya confirmado solo se vuelve a generar si cambian las marcas
    return !this.procesando && this.tieneResultado && !this.hayPendientes &&
      (this.doc.status !== DocState.COMPLETED || this.hayCambios);
  }

  get textoVersion(): string {
    const lectura = this.soloLectura ? ' · solo lectura' : '';
    if (this.doc.status === DocState.REVIEW_READY) return 'Vista previa · pendiente de confirmar' + lectura;
    if (this.doc.status === DocState.COMPLETED) return 'Versión definitiva' + lectura;
    return '';
  }

  /** Mensaje del servicio al terminar bien */
  get mensajeFin(): string {
    const terminado = this.doc.status === DocState.REVIEW_READY || this.doc.status === DocState.COMPLETED;
    return terminado ? (this.doc.resultMessage ?? '') : '';
  }

  /** Ayuda breve bajo la barra del visor */
  get ayudaVisor(): string {
    if (this.soloLectura) return 'Solo lectura. Para cambiar algo, vuelve a la lista y pulsa «Revisar» (se analizará de nuevo).';
    if (this.modoZona) return 'Arrastra sobre la página, o marca dos esquinas opuestas. Esc cancela.';
    const definitiva = this.doc.status === DocState.COMPLETED && !this.mostrandoOriginal;
    return definitiva
      ? 'En la versión definitiva no se puede seleccionar texto ni marcar zonas: cambia a «Original».'
      : 'Selecciona texto para censurarlo siempre, o, en «Original», usa «Marcar zona» para tapar un área en todas las páginas.';
  }

  ngOnInit(): void {
    const doc = this.docStateService.getDocumento();
    // Solo se revisan documentos que siguen en la bandeja
    if (!doc || !this.documentService.documents().includes(doc)) {
      this.salir();
      return;
    }
    this.doc = doc;
    this.soloLectura = this.docStateService.esSoloLectura();
    this.esPdf = doc.file.type === 'application/pdf';
    this.paginasListas = !this.esPdf;
    this.cargarDetecciones();
    this.actualizarVisor();
    if (this.esPdf && !this.soloLectura) this.escucharSeleccion();

    this.finSub = this.procesamiento.terminado$.subscribe(d => {
      if (d.id === this.doc.id) this.alTerminar();
    });
  }

  ngAfterViewInit(): void {
    const contenedor = this.visor?.nativeElement;
    if (!contenedor || !this.esPdf) return;
    // Los gestos del ratón van fuera de la zona de Angular; solo se entra al crear una zona
    this.ngZone.runOutsideAngular(() => {
      this.captura = new CapturaZonas(
        contenedor,
        pagina => this.vistaDePagina(pagina),
        (pagina, zona) => this.ngZone.run(() => this.alMarcarZona(pagina, zona)),
        mensaje => this.ngZone.run(() => this.avisar(mensaje))
      );
    });
  }

  ngOnDestroy(): void {
    // Solo se deja de escuchar: el procesamiento continúa en segundo plano
    this.finSub?.unsubscribe();
    this.quitarEscuchas.forEach(quitar => quitar());
    this.quitarEscuchas = [];
    window.clearTimeout(this.temporizadorSeleccion);
    window.clearTimeout(this.temporizadorMensaje);
    window.clearTimeout(this.temporizadorAvisoPanel);
    this.captura?.destruir();
  }

  @HostListener('document:keydown.escape')
  alEscape(): void {
    if (this.ajustesEnEdicion) return; // el diálogo gestiona su propio Esc
    if (this.captura?.cancelar()) return;
    if (this.modoZona) {
      this.alternarModoZona();
    } else if (this.seleccion) {
      this.seleccion = null;
    } else if (this.avisoPanel) {
      this.cerrarAvisoPanel();
    }
  }

  // ---------------------------------------------------------------------------
  // Visor
  // ---------------------------------------------------------------------------

  onPdfLoaded(pdfInfo: any): void {
    this.pdfDoc = pdfInfo;
    this.captura?.vaciarVistas();
    this.totalPages = pdfInfo.numPages;
    this.paginasListas = true;
  }

  /** Tamaño de la página en puntos PDF, con su rotación, para convertir los clics */
  private async vistaDePagina(pagina: number) {
    try {
      const p = await this.pdfDoc?.getPage(pagina);
      return p?.getViewport({ scale: 1 });
    } catch (err) {
      console.error('No se pudo leer la página del PDF', err);
      return undefined;
    }
  }

  mostrarVersion(original: boolean): void {
    if (!original && this.modoZona) this.salirDeModoZona();
    this.verOriginal = original;
    this.actualizarVisor();
  }

  actualizarVisor(): void {
    const base64 = this.mostrandoOriginal ? this.doc.originalBase64 : this.doc.finalBase64;

    if (!base64 || typeof base64 !== 'string') {
      console.error('Documento no disponible para el visor');
      return;
    }

    if (this.esPdf) {
      const rawBase64 = base64.includes(',') ? base64.split(',')[1] : base64;
      try {
        const binaryString = window.atob(rawBase64);
        const bytes = new Uint8Array(binaryString.length);
        for (let i = 0; i < binaryString.length; i++) {
          bytes[i] = binaryString.charCodeAt(i);
        }
        this.pdfSource = bytes;
      } catch (e) {
        console.error('Error al decodificar el documento', e);
      }
    } else {
      this.pdfSource = `data:${this.doc.file.type};base64,${base64}`;
    }
  }

  cambiarPagina(delta: number): void {
    const nuevaPagina = this.currentPage + delta;
    if (nuevaPagina >= 1 && nuevaPagina <= this.totalPages) {
      this.currentPage = nuevaPagina;
      this.filtrarDeteccionesPagina();
    }
  }

  cambiarZoom(delta: number): void {
    this.zoom = Math.max(0.5, Math.min(3.0, this.zoom + delta));
  }

  // ---------------------------------------------------------------------------
  // Selección en el visor: frases y zonas
  // ---------------------------------------------------------------------------

  alternarModoZona(): void {
    this.modoZona = !this.modoZona;
    this.seleccion = null;
    window.getSelection()?.removeAllRanges();
    this.captura?.activar(this.modoZona);
  }

  /** Añade a pendientes la frase seleccionada y quita el botón flotante */
  censurarSeleccion(): void {
    if (!this.seleccion) return;
    const texto = this.seleccion.texto;
    this.seleccion = null;
    window.getSelection()?.removeAllRanges();
    this.anadirFrase(texto);
  }

  quitarPendiente(indice: number): void {
    this.pendientes = this.pendientes.filter((_, i) => i !== indice);
  }

  descartarPendientes(): void {
    this.pendientes = [];
  }

  /** Aplica todos los pendientes con un único análisis completo, conservando las decisiones */
  aplicarPendientes(): void {
    if (!this.puedeAplicar) return;
    const ajustes = this.ajustesConPendientes(this.ajustesActuales());
    this.pendientes = [];
    this.salirDeModoZona();
    this.analizar(ajustes);
  }

  textoPendiente(p: Pendiente): string {
    return p.tipo === 'frase' ? `«${recortar(p.texto, 40)}»` : 'Zona · todas las páginas';
  }

  /** Detalle del chip de un pendiente, al pasar el ratón */
  tituloPendiente(p: Pendiente): string {
    return p.tipo === 'frase'
      ? p.texto
      : `Marcada en la página ${p.pagina}. Se censura en todas; desactívala en el panel donde no toque.`;
  }

  /** Sale del modo «marcar zona» y descarta la zona que estuviera a medio marcar */
  private salirDeModoZona(): void {
    this.captura?.cancelar();
    this.captura?.activar(false);
    this.modoZona = false;
  }

  /** Ajustes del documento, con la lista de zonas de página siempre presente */
  private ajustesActuales(): AjustesDocumento {
    const ajustes = this.procesamiento.ajustesDe(this.doc);
    ajustes.zonasPagina ??= [];
    return ajustes;
  }

  /**
   * Copia de los ajustes con lo pendiente del visor incorporado: las frases van a la lista negra
   * y las zonas a las censuras fijas (el motor no tiene página para ellas: se aplican en todas).
   */
  private ajustesConPendientes(base: AjustesDocumento): AjustesDocumento {
    const ajustes = structuredClone(base);
    for (const p of this.pendientes) {
      if (p.tipo === 'frase') {
        if (!ajustes.bloqueadas.some(f => mismaFrase(f, p.texto))) ajustes.bloqueadas.push(p.texto);
      } else if (!contieneZona(ajustes.zonasFijas, p.zona)) {
        ajustes.zonasFijas.push({ ...p.zona });
      }
    }
    return ajustes;
  }

  private anadirFrase(texto: string): void {
    if (this.procesando) return;
    const motivo = motivoFraseNoValida(texto);
    if (motivo) {
      this.avisar(motivo);
      return;
    }
    const enPendientes = this.pendientes.flatMap(p => (p.tipo === 'frase' ? [p.texto] : []));
    const existentes = [...this.ajustesActuales().bloqueadas, ...enPendientes];
    if (existentes.some(f => mismaFrase(f, texto))) {
      this.avisar('Esa frase ya está en la lista negra de este documento.');
      return;
    }
    this.pendientes = [...this.pendientes, { tipo: 'frase', texto }];
  }

  private alMarcarZona(pagina: number, zona: Coordinate): void {
    this.modoZona = false;
    this.captura?.activar(false);
    if (this.procesando) return;

    const repetida = contieneZona(this.zonasPendientes(), zona)
      || contieneZona(this.ajustesActuales().zonasFijas, zona);
    if (repetida) {
      this.avisar('Esa zona ya está entre las censuras fijas de este documento.');
      return;
    }
    this.pendientes = [...this.pendientes, { tipo: 'zona', pagina, zona }];
  }

  private zonasPendientes(): Coordinate[] {
    return this.pendientes.flatMap(p => (p.tipo === 'zona' ? [p.zona] : []));
  }

  private avisar(texto: string): void {
    this.mensaje = texto;
    window.clearTimeout(this.temporizadorMensaje);
    this.temporizadorMensaje = window.setTimeout(() => (this.mensaje = ''), DURACION_AVISO_MS);
  }

  // --- Selección de texto -----------------------------------------------------

  /**
   * El botón flotante aparece al terminar una selección dentro de la capa de texto del visor
   * y desaparece al perderla. Los escuchas van fuera de la zona de Angular: solo se entra en
   * ella cuando cambia lo que se muestra.
   */
  private escucharSeleccion(): void {
    const enVisor = (destino: EventTarget | null) =>
      destino instanceof Node && !!this.visor?.nativeElement.contains(destino);

    const alPulsar = (e: Event) => { this.arrastrandoRaton = enVisor(e.target); };
    const alSoltar = () => {
      if (!this.arrastrandoRaton) return;
      this.arrastrandoRaton = false;
      window.setTimeout(() => this.evaluarSeleccion());
    };
    const alAbandonar = () => { this.arrastrandoRaton = false; };
    // Táctil y teclado: la selección termina sin pointerup; se evalúa cuando se estabiliza
    const alCambiar = () => {
      window.clearTimeout(this.temporizadorSeleccion);
      this.temporizadorSeleccion = window.setTimeout(() => {
        if (!this.arrastrandoRaton) this.evaluarSeleccion();
      }, 300);
    };
    const alDesplazar = () => this.ocultarSeleccion();

    this.ngZone.runOutsideAngular(() => {
      document.addEventListener('pointerdown', alPulsar);
      document.addEventListener('pointerup', alSoltar);
      document.addEventListener('pointercancel', alAbandonar);
      document.addEventListener('selectionchange', alCambiar);
      document.addEventListener('scroll', alDesplazar, true);
    });
    this.quitarEscuchas.push(
      () => document.removeEventListener('pointerdown', alPulsar),
      () => document.removeEventListener('pointerup', alSoltar),
      () => document.removeEventListener('pointercancel', alAbandonar),
      () => document.removeEventListener('selectionchange', alCambiar),
      () => document.removeEventListener('scroll', alDesplazar, true)
    );
  }

  private evaluarSeleccion(): void {
    if (this.modoZona) return;
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) {
      this.ocultarSeleccion();
      return;
    }
    const rango = sel.getRangeAt(0);
    if (!this.enCapaTexto(rango.commonAncestorContainer)) {
      this.ocultarSeleccion();
      return;
    }
    const texto = normalizarFrase(sel.toString());
    if (!texto) {
      this.ocultarSeleccion();
      return;
    }
    const caja = rango.getBoundingClientRect();
    const x = Math.min(Math.max(caja.left + caja.width / 2, 150), window.innerWidth - 150);
    const y = Math.min(caja.bottom + 8, window.innerHeight - 48);
    this.ngZone.run(() => (this.seleccion = { texto, x, y }));
  }

  private ocultarSeleccion(): void {
    if (this.seleccion) this.ngZone.run(() => (this.seleccion = null));
  }

  /** El nodo está en la capa de texto de pdf.js dentro del visor */
  private enCapaTexto(nodo: Node): boolean {
    const elemento = nodo.nodeType === Node.ELEMENT_NODE ? (nodo as Element) : nodo.parentElement;
    return !!elemento && !!this.visor?.nativeElement.contains(elemento) && !!elemento.closest('.textLayer');
  }

  // ---------------------------------------------------------------------------
  // Detecciones
  // ---------------------------------------------------------------------------

  filtrarDeteccionesPagina(): void {
    this.deteccionesPagina = this.todasDetecciones.filter(d => (d.page || 1) === this.currentPage);
  }

  /** Apariciones y nemotécnico de zona de una detección */
  infoDe(item: RedactionItem): InfoDeteccion {
    return this.info.get(item) ?? { grupo: [item], apariciones: '' };
  }

  /**
   * Interruptor de una detección. Con Mayús (clic o Espacio) se aplica a todas las apariciones
   * del mismo elemento. Si no, solo a esta; y si hay otras con distinto estado, se ofrece
   * aplicarlo a todas en el aviso del panel.
   */
  toggleDeteccion(item: RedactionItem, evento?: MouseEvent): void {
    const censurar = !estaCensurada(item);
    const grupo = this.infoDe(item).grupo;

    if (evento?.shiftKey && grupo.length > 1) {
      this.cambiarEnTodas(item, censurar);
      return;
    }

    fijarCensura(item, censurar);
    this.hayCambios = true;

    const hayDistintas = grupo.some(m => m !== item && estaCensurada(m) !== censurar);
    if (!hayDistintas) {
      this.cerrarAvisoPanel();
      return;
    }
    this.mostrarAvisoPanel({
      texto: `${censurar ? 'Activado' : 'Desactivado'} en esta página: ${this.nombreDeteccion(item)}`,
      accion: `${censurar ? 'Activar' : 'Desactivar'} ${textoTodas(grupo)}`,
      ejecutar: () => this.cambiarEnTodas(item, censurar)
    });
  }

  /** Aplica el mismo estado a todas las apariciones del elemento, con opción de deshacer */
  private cambiarEnTodas(item: RedactionItem, censurar: boolean): void {
    const grupo = this.infoDe(item).grupo;
    const antes = grupo.map(m => ({ marca: m, action: m.action, apply: m.apply }));
    grupo.forEach(m => fijarCensura(m, censurar));
    this.hayCambios = true;

    this.mostrarAvisoPanel({
      texto: `${censurar ? 'Activado' : 'Desactivado'} ${textoTodas(grupo)}: ${this.nombreDeteccion(item)}`,
      accion: 'Deshacer',
      ejecutar: () => {
        antes.forEach(a => {
          a.marca.action = a.action;
          a.marca.apply = a.apply;
        });
        this.cerrarAvisoPanel();
      }
    });
  }

  /** Tooltip del interruptor: indica el atajo solo si hay más apariciones */
  tituloInterruptor(item: RedactionItem): string {
    return this.infoDe(item).grupo.length > 1
      ? 'Activar para censurar · Mayús + clic: en todas sus apariciones'
      : 'Activar para censurar';
  }

  getIcono(tipo: string): string {
    switch (tipo) {
      case 'FACE': return '👁️';
      case 'PERSON_NAME': return '👤';
      case 'SENSITIVE_DATA': return '🔒';
      case 'QR_CODE': return '📱';
      case 'NAMED_ENTITY': return '🏷️';
      case 'FIXED_ZONE': return '⬛';
      case 'IMAGE': return '🖼️';
      default: return '📄';
    }
  }

  /** Nombre del tipo de detección para el panel */
  nombreTipo(item: RedactionItem): string {
    if (item.description === DESCRIPCION_ZONA_MANUAL) return 'Zona del visor';
    const tipo = String(item.detectionType);
    return NOMBRES_TIPO[tipo] ?? tipo.toLowerCase();
  }

  /** Cómo se nombra una detección en los avisos y para los lectores de pantalla */
  nombreDeteccion(item: RedactionItem): string {
    if (item.text) return `«${recortar(normalizarFrase(item.text), 30)}»`;
    const zona = this.infoDe(item).zona;
    return zona ? `${zona} (censura fija)` : this.nombreTipo(item);
  }

  private cargarDetecciones(): void {
    this.todasDetecciones = structuredClone(this.doc.censoredItems || []);
    if (!this.esPdf) this.todasDetecciones.forEach(d => d.page = 1);
    this.calcularInfo();
    this.hayCambios = false;
    this.cerrarAvisoPanel();
    this.filtrarDeteccionesPagina();
  }

  /**
   * Agrupa las apariciones de cada elemento y da a cada censura fija su nemotécnico:
   * Zn es la zona n del campo «Censuras fijas»; las que no están ahí toman los números siguientes.
   */
  private calcularInfo(): void {
    const grupos = agruparApariciones(this.todasDetecciones);
    const fijas = this.ajustesActuales().zonasFijas;
    const otrasZonas = new Map<string, string>();
    this.info = new Map();

    for (const marca of this.todasDetecciones) {
      const grupo = grupos.get(marca) ?? [marca];
      const info: InfoDeteccion = { grupo, apariciones: textoApariciones(grupo) };
      const zona = zonaDe(marca);

      if (String(marca.detectionType) === TIPO_ZONA_FIJA && zona) {
        const coordenadas = textoZona(zona);
        const indice = fijas.findIndex(z => mismaZona(z, zona));
        info.coordenadas = coordenadas;
        if (indice >= 0) {
          info.zona = `Z${indice + 1}`;
          info.tituloZona = `Zona ${indice + 1} del campo «Censuras fijas» en «Repetir análisis»`;
        } else {
          if (!otrasZonas.has(coordenadas)) {
            otrasZonas.set(coordenadas, `Z${fijas.length + otrasZonas.size + 1}`);
          }
          info.zona = otrasZonas.get(coordenadas);
          info.tituloZona = 'Zona que no está en el campo «Censuras fijas»';
        }
      }
      this.info.set(marca, info);
    }
  }

  private alTerminar(): void {
    // Con error se conservan las marcas del panel para poder reintentar
    if (this.doc.status !== DocState.ERROR) {
      this.cargarDetecciones();
      this.verOriginal = false;
    }
    this.actualizarVisor();
  }

  // --- Aviso del panel ----------------------------------------------------------

  private mostrarAvisoPanel(aviso: AvisoPanel): void {
    this.avisoPanel = aviso;
    window.clearTimeout(this.temporizadorAvisoPanel);
    this.temporizadorAvisoPanel = window.setTimeout(() => (this.avisoPanel = null), DURACION_AVISO_PANEL_MS);
  }

  cerrarAvisoPanel(): void {
    window.clearTimeout(this.temporizadorAvisoPanel);
    this.avisoPanel = null;
  }

  ejecutarAvisoPanel(): void {
    const ejecutar = this.avisoPanel?.ejecutar;
    if (ejecutar && !this.procesando) ejecutar();
  }

  // ---------------------------------------------------------------------------
  // Acciones de la revisión (todas pasan por el servicio de procesamiento)
  // ---------------------------------------------------------------------------

  /** Regenera la vista previa con los interruptores del panel, sin volver a detectar. */
  actualizarVistaPrevia(): void {
    this.cerrarAvisoPanel();
    this.procesamiento.actualizarVistaPrevia(this.doc, this.todasDetecciones);
  }

  /**
   * Abre «Repetir análisis» con los ajustes actuales del documento (FEAT-002).
   * Los cambios pendientes del visor se muestran ya incorporados; si se cancela, siguen pendientes.
   * Si se estaba marcando una zona, se deja de marcar.
   */
  abrirReprocesar(): void {
    if (!this.puedeReprocesar) return;
    try {
      const actuales = this.ajustesActuales();
      this.ajustesIniciales = this.hayPendientes ? this.ajustesConPendientes(actuales) : undefined;
      if (this.modoZona) this.salirDeModoZona();
      this.seleccion = null;
      this.ajustesEnEdicion = actuales;
    } catch (err) {
      console.error('No se ha podido abrir «Repetir análisis»', err);
      this.avisar('No se ha podido abrir «Repetir análisis». Revisa la consola.');
    }
  }

  cerrarReprocesar(): void {
    this.ajustesEnEdicion = null;
    this.ajustesIniciales = undefined;
  }

  onReprocesarConfirm(resultado: ResultadoRepetir): void {
    this.cerrarReprocesar();
    this.pendientes = []; // ya van dentro de los ajustes que devuelve el diálogo
    this.analizar(resultado.ajustes);
  }

  /**
   * Análisis completo con los ajustes indicados (reconstructionMode=false). Las decisiones del
   * panel, también las aún no aplicadas, se conservan al terminar.
   */
  private analizar(ajustes: AjustesDocumento): void {
    this.cerrarAvisoPanel();
    this.procesamiento.repetirAnalisis(this.doc, ajustes, this.todasDetecciones);
  }

  /** Genera el documento definitivo en segundo plano y vuelve a la bandeja. */
  confirmar(): void {
    if (!this.puedeConfirmar) return;
    this.cerrarAvisoPanel();
    this.procesamiento.confirmar(this.doc, this.todasDetecciones);
    this.salir();
  }

  volver(): void {
    if (this.hayPendientes && !confirm('Hay cambios del visor sin aplicar. ¿Salir y descartarlos?')) {
      return;
    }
    if (this.hayCambios && !this.procesando &&
        !confirm('Hay marcas cambiadas sin aplicar. ¿Salir y descartar esos cambios?')) {
      return;
    }
    this.salir();
  }

  private salir(): void {
    this.docStateService.limpiar();
    this.router.navigate(['/upload']);
  }
}