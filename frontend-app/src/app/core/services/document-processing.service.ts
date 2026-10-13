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

import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { Subject, Subscription, filter, firstValueFrom } from 'rxjs';
import { ApiService } from './api.service';
import { AppConfigService } from './app-config.service';
import { DocumentService } from './document.service';
import { DocumentStateService } from './document-state.service';
import { UserService } from './user.service';
import {
  BatchDocumentItem,
  DocState,
  DocumentItemUI,
  FaseServicio,
  GetStatusResponse,
  ProcessingOptions,
  ProcessStateEnum,
  RedactionItem,
  SubmitBatchResponse
} from '../interfaces/types';
import { avisoDe, esSaturado, esperarResultado, mensajeError, mensajeEstado } from '../utils/estado-documento';
import {
  AjustesDocumento,
  ajustesDesdeOpciones,
  combinarOpciones,
  opcionesDesdePreferencias
} from '../utils/opciones-procesamiento';
import { conservarDecisiones, marcasConZonasPagina, mismasMarcas } from '../utils/marcas';

/** Estados en los que el documento está en cola o en manos del servidor */
const ESTADOS_EN_PROCESO: DocState[] = [
  DocState.PENDING,
  DocState.UPLOADING,
  DocState.POLLING_PREVIEW,
  DocState.POLLING_FINAL
];

/** Ruta de la pantalla de acceso: llegar a ella significa que la sesión ha terminado */
const RUTA_ACCESO = '/login';

/**
 * Archivos de alta que un usuario tiene a la vez en el servidor. El resto espera en el cliente,
 * para que unos usuarios no llenen la cola del servidor (4 workers + 12 en cola, compartida).
 * Igual que MAX_POR_CLIENTE de AsyncJobManager.
 */
const MAX_ALTAS_SIMULTANEAS = 2;

/** Un archivo mayor que esto ocupa todas las plazas: los grandes van de uno en uno */
const MB_ARCHIVO_GRANDE = 5;
/** Espera mínima antes de reenviar lo que el servidor rechaza por saturado (se le suma hasta otro tanto) */
const ESPERA_REINTENTO_MS = 5000;


const MENSAJE_NO_ACEPTADO = 'El servidor no ha aceptado el documento. Vuelva a intentarlo.';
const MENSAJE_SIN_PREFERENCIAS =
  'No se han podido cargar sus preferencias de procesamiento, así que no se ha añadido ningún archivo. ' +
  'Vuelva a intentarlo en unos segundos.';
const MENSAJE_SIN_PREFERENCIAS_ANALISIS =
  'No se han podido cargar sus preferencias de procesamiento. Vuelva a repetir el análisis en unos segundos.';

/** Resultado de añadir archivos a la bandeja */
export interface ResultadoAlta {
  /** Motivo por el que no se ha añadido ningún archivo */
  error?: string;
  /** Un mensaje por cada archivo rechazado */
  rechazos: string[];
}

/**
 * Lo que hay que reaplicar sobre el resultado de un análisis completo: el motor no recibe
 * marcas en ese modo, así que las decisiones del panel y las zonas de una página se
 * incorporan después y, si hay diferencias, con una reconstrucción interna.
 */
interface Fusion {
  /** Marcas del panel antes del análisis, con las decisiones del usuario (aplicadas o no) */
  previas: RedactionItem[];
  antes: AjustesDocumento;
  despues: AjustesDocumento;
}

/** Datos que pasan de un envío al siguiente cuando se encadenan */
interface Cadena {
  fusion?: Fusion;
  /** Aviso del análisis */
  aviso?: string;
  /** Resumen del análisis */
  mensaje?: string;
}

/**
 * Gestión centralizada del procesamiento de documentos: alta en la bandeja,
 * envío al motor, seguimiento del ticket y estado de cada documento.
 *
 * Es un servicio de aplicación: el procesamiento continúa aunque el usuario
 * cambie de pantalla. Las pantallas solo piden acciones y leen el estado.
 *
 * Modos del motor:
 * - Análisis completo (reconstructionMode=false): alta de archivos y «Repetir análisis»,
 *   tanto desde el diálogo como desde los cambios del visor.
 * - Reconstrucción (reconstructionMode=true): solo con las marcas del panel, al
 *   «Actualizar vista previa» y al «Confirmar», y como paso interno tras un análisis
 *   para reaplicar decisiones y zonas de una página.
 */
@Injectable({ providedIn: 'root' })
export class DocumentProcessingService {
  private apiService = inject(ApiService);
  private config = inject(AppConfigService);
  private documentService = inject(DocumentService);
  private docStateService = inject(DocumentStateService);
  private userService = inject(UserService);
  private router = inject(Router);

  private terminado = new Subject<DocumentItemUI>();
  /** Emite cada documento que termina su procesamiento, con resultado o con error. */
  readonly terminado$ = this.terminado.asObservable();

  private seguimientos = new Map<string, Subscription>(); // consulta de estado en curso de cada documento
  private envios = new Map<string, number>();              // envío vigente de cada documento
  private ajustes = new Map<string, AjustesDocumento>();   // ajustes propios de cada documento
  private ultimoEnvio = 0;
  private sesion = 0;                                      // cambia al vaciar la bandeja; invalida altas en curso
  private colaAltas: { doc: DocumentItemUI; opciones: ProcessingOptions }[] = []; // altas que esperan en el cliente
  private altasEnCurso = new Map<string, number>();        // alta en el servidor → plazas que ocupa
  private altasEnPausa = false;                            // el servidor está saturado: no sale ninguna alta

  constructor() {
    // Los documentos no sobreviven a la sesión: al llegar a la pantalla de acceso
    // (salir, sesión caducada o redirección de un guard) se vacía la bandeja
    this.router.events.subscribe(evento => {
      if (evento instanceof NavigationEnd && evento.urlAfterRedirects.split(/[?#]/)[0] === RUTA_ACCESO) {
        this.descartarTodos();
      }
    });

    // Los documentos solo están en memoria: recargar o cerrar la pestaña los pierde.
    // El navegador muestra su propio aviso; el texto no se puede cambiar
    window.addEventListener('beforeunload', evento => {
      if (this.documentService.documents().length > 0) {
        evento.preventDefault();
        evento.returnValue = ''; // navegadores que no atienden preventDefault
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Consultas
  // ---------------------------------------------------------------------------

  estaEnProceso(doc: DocumentItemUI): boolean {
    return ESTADOS_EN_PROCESO.includes(doc.status);
  }

  hayEnProceso(): boolean {
    return this.documentService.documents().some(d => this.estaEnProceso(d));
  }

  /**
   * Ajustes actuales del documento (una copia). Si aún no tiene propios, los de las
   * opciones con que se analizó, que son las globales del momento de la subida.
   */
  ajustesDe(doc: DocumentItemUI): AjustesDocumento {
    return structuredClone(this.ajustes.get(doc.id) ?? ajustesDesdeOpciones(doc.options));
  }

  /** Ajustes de las opciones globales vigentes del usuario */
  async ajustesGlobales(): Promise<AjustesDocumento> {
    return ajustesDesdeOpciones(await this.opcionesUsuario());
  }

  // ---------------------------------------------------------------------------
  // Acciones
  // ---------------------------------------------------------------------------

  /**
   * Añade los archivos a la bandeja y lanza su procesamiento con las preferencias
   * vigentes del usuario, que se leen en este momento.
   */
  async anadirArchivos(archivos: File[]): Promise<ResultadoAlta> {
    const sesion = this.sesion;

    let opciones: ProcessingOptions;
    try {
      opciones = await this.opcionesUsuario();
    } catch (err) {
      if (sesion !== this.sesion) return { rechazos: [] }; // la sesión ha terminado mientras tanto
      console.error('Error al cargar las preferencias', err);
      return { error: MENSAJE_SIN_PREFERENCIAS, rechazos: [] };
    }
    if (sesion !== this.sesion) return { rechazos: [] };

    const rechazos: string[] = [];
    const nuevos: DocumentItemUI[] = [];
    const maxMB = this.config.maxUploadSizeMB();

    for (const file of archivos) {
      if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
        rechazos.push(`"${file.name}": formato no admitido. Solo se aceptan PDF, JPG y PNG.`);
        continue;
      }
      const tamanoMB = file.size / 1024 / 1024;
      if (tamanoMB > maxMB) {
        rechazos.push(`"${file.name}" ocupa ${tamanoMB.toFixed(1)} MB. El máximo permitido es ${maxMB} MB.`);
        continue;
      }
      try {
        const originalBase64 = await leerBase64(file);
        if (sesion !== this.sesion) return { rechazos: [] };
        const doc: DocumentItemUI = {
          id: crypto.randomUUID(),
          file,
          contentType: file.type,
          originalBase64,
          status: DocState.PENDING,
          estadoServicio: 'EN_COLA', // hasta que le llegue el turno de envío
          options: structuredClone(opciones)
        };
        this.documentService.addDocument(doc);
        nuevos.push(doc);
      } catch {
        rechazos.push(`"${file.name}": no se ha podido leer el archivo.`);
      }
    }

    // Un envío por documento: el límite es por archivo, y en un único lote varios
    // archivos grandes superarían juntos el tamaño máximo de la petición.
    // Se envían por turnos (ver lanzarAltas), no todos a la vez
    for (const doc of nuevos) this.colaAltas.push({ doc, opciones });
    this.lanzarAltas();
    return { rechazos };
  }

  /**
   * Repite el análisis del documento con nuevos ajustes desde la revisión: siempre es un
   * análisis completo (reconstructionMode=false) con las preferencias vigentes y los ajustes.
   *
   * Al terminar se reaplican las decisiones del panel (emparejando por tipo, texto y página)
   * y las zonas de una página. Si eso cambia algo, el servicio encadena una reconstrucción
   * interna; para el usuario sigue siendo un único análisis.
   *
   * @param marcas marcas actuales del panel, con las decisiones del usuario (aplicadas o no)
   */
  repetirAnalisis(doc: DocumentItemUI, ajustes: AjustesDocumento, marcas: RedactionItem[]): void {
    doc.modificadoEnRevision = true;
    const fusion: Fusion = {
      previas: structuredClone(marcas),
      antes: this.ajustesDe(doc),
      despues: structuredClone(ajustes)
    };
    this.reprocesar(doc, ajustes, fusion);
  }

  /**
   * Vuelve a procesar el documento como recién subido: preferencias vigentes del usuario,
   * sin los ajustes propios ni las decisiones de revisiones anteriores.
   */
  async reiniciar(doc: DocumentItemUI): Promise<void> {
    this.detener(doc.id);
    const envio = ++this.ultimoEnvio;
    this.envios.set(doc.id, envio);
    this.ajustes.delete(doc.id);
    doc.status = DocState.PENDING;
    doc.resultMessage = undefined;
    doc.avisoMessage = undefined;
    doc.estadoServicio = undefined;
    doc.finalBase64 = undefined;
    doc.censoredItems = undefined;
    doc.modificadoEnRevision = false;

    let opciones: ProcessingOptions;
    try {
      opciones = await this.opcionesUsuario();
    } catch (err) {
      if (this.esVigente(doc, envio)) {
        console.error('Error al cargar las preferencias', err);
        this.marcarError(doc, MENSAJE_SIN_PREFERENCIAS_ANALISIS);
      }
      return;
    }
    if (!this.esVigente(doc, envio)) return;

    // Igual que en anadirArchivos
    doc.options = structuredClone(opciones);
    this.enviar([doc], opciones);
  }

  /**
   * Regenera la vista previa con las marcas del panel, sin volver a detectar
   * (reconstructionMode=true). Solo para los interruptores del panel de detecciones.
   */
  actualizarVistaPrevia(doc: DocumentItemUI, marcas: RedactionItem[]): void {
    doc.modificadoEnRevision = true;
    // Se envían las opciones del documento: el dibujo usa el grosor del tachado, entre otras
    this.enviar([doc], { ...doc.options, redactionPreview: true, reconstructionMode: true }, marcas);
  }

  /** Genera el documento definitivo con las marcas confirmadas. */
  confirmar(doc: DocumentItemUI, marcas: RedactionItem[]): void {
    doc.modificadoEnRevision = true;
    this.enviar([doc], { ...doc.options, redactionPreview: false, reconstructionMode: true }, marcas);
  }

  /** Quita el documento de la bandeja y deja de seguir su procesamiento. */
  descartar(doc: DocumentItemUI): void {
    this.detener(doc.id);
    this.liberarAlta(doc.id);
    this.ajustes.delete(doc.id);
    this.documentService.removeDocument(doc.id);
  }

  /** Vacía la bandeja y cancela todo lo que esté en curso (p. ej. al terminar la sesión). */
  descartarTodos(): void {
    this.sesion++;
    this.seguimientos.forEach(s => s.unsubscribe());
    this.seguimientos.clear();
    this.envios.clear();
    this.ajustes.clear();
    this.colaAltas = [];
    this.altasEnCurso.clear();
    this.docStateService.limpiar();
    this.documentService.clearAll();
  }

  // ---------------------------------------------------------------------------
  // Envío y seguimiento
  // ---------------------------------------------------------------------------

  /**
   * Análisis completo con las preferencias vigentes y los ajustes del documento.
   * El resultado siempre llega como vista previa; al terminar se reaplican las decisiones
   * del panel y las zonas de una página (ver Fusion).
   */
  private async reprocesar(doc: DocumentItemUI, ajustes: AjustesDocumento, fusion: Fusion): Promise<void> {
    this.detener(doc.id);
    const envio = ++this.ultimoEnvio;
    this.envios.set(doc.id, envio); // cualquier otra acción o un descarte invalida este análisis
    this.ajustes.set(doc.id, structuredClone(ajustes));
    doc.status = DocState.PENDING;  // en cola mientras se leen las preferencias
    doc.resultMessage = undefined;
    doc.avisoMessage = undefined;
    doc.estadoServicio = undefined;

    let preferencias: ProcessingOptions;
    try {
      preferencias = await this.opcionesUsuario();
    } catch (err) {
      if (this.esVigente(doc, envio)) {
        console.error('Error al cargar las preferencias', err);
        this.marcarError(doc, MENSAJE_SIN_PREFERENCIAS_ANALISIS);
      }
      return;
    }
    if (!this.esVigente(doc, envio)) return;

    doc.options = combinarOpciones(preferencias, ajustes);
    this.enviar([doc], { ...doc.options, redactionPreview: true, reconstructionMode: false }, undefined, { fusion });
  }

  /** Preferencias vigentes del usuario, en la forma que espera el motor */
  private async opcionesUsuario(): Promise<ProcessingOptions> {
    const usuario: any = await firstValueFrom(this.userService.getSettings());
    return opcionesDesdePreferencias(usuario?.settings?.sensiaOptions);
  }


  /**
   * Envía las altas en espera mientras haya plazas, por orden de llegada. Un archivo grande
   * ocupa todas, así que espera a que terminen los anteriores y nadie entra hasta que acabe.
   */
  private lanzarAltas(): void {
    while (!this.altasEnPausa && this.colaAltas.length > 0) {
      const { doc, opciones } = this.colaAltas[0];
      const plazas = doc.file.size > MB_ARCHIVO_GRANDE * 1024 * 1024 ? MAX_ALTAS_SIMULTANEAS : 1;
      const ocupadas = [...this.altasEnCurso.values()].reduce((a, b) => a + b, 0);
      if (ocupadas + plazas > MAX_ALTAS_SIMULTANEAS) return;
      this.colaAltas.shift();
      this.altasEnCurso.set(doc.id, plazas); // antes de enviar(): un rechazo puede llegar enseguida
      this.enviar([doc], opciones);
    }
  }

  /** Libera las plazas del documento, si era un alta en curso, y da paso a las siguientes */
  private liberarAlta(id: string): void {
    if (this.altasEnCurso.delete(id)) this.lanzarAltas();
  }


  /**
   * El servidor está saturado: los documentos esperan en cola y se reenvían pasados unos
   * segundos. Un alta vuelve al principio de la espera y las altas se pausan
   */
  private esperarTurno(docs: DocumentItemUI[], envio: number, opciones: ProcessingOptions,
                       marcas?: RedactionItem[], cadena?: Cadena): void {
    const espera = ESPERA_REINTENTO_MS * (1 + Math.random());
    const altas = docs.filter(d => this.altasEnCurso.has(d.id));
    const otros = docs.filter(d => !this.altasEnCurso.has(d.id));
    for (const doc of docs) {
      doc.status = DocState.PENDING;
      doc.estadoServicio = 'EN_COLA';
    }

    if (altas.length > 0) {
      altas.forEach(d => this.altasEnCurso.delete(d.id));
      this.colaAltas.unshift(...altas.map(doc => ({ doc, opciones })));
      if (!this.altasEnPausa) {
        this.altasEnPausa = true;
        setTimeout(() => {
          this.altasEnPausa = false;
          this.lanzarAltas();
        }, espera);
      }
    }
    if (otros.length > 0) {
      setTimeout(() => {
        const siguen = otros.filter(d => this.esVigente(d, envio));
        if (siguen.length > 0) this.enviar(siguen, opciones, marcas, cadena);
      }, espera);
    }
  }

  

  private enviar(docs: DocumentItemUI[], opciones: ProcessingOptions, marcas?: RedactionItem[],
                 cadena?: Cadena): void {
    const envio = ++this.ultimoEnvio;
    const preview = !!opciones.redactionPreview;
    // Copia: el panel de revisión puede seguir tocando sus marcas mientras tanto
    const marcasEnviadas = marcas ? structuredClone(marcas) : undefined;

    for (const doc of docs) {
      this.detener(doc.id); // un documento solo tiene un procesamiento vigente
      this.envios.set(doc.id, envio);
      doc.status = DocState.UPLOADING;
      doc.serverTicketId = undefined;
      doc.resultMessage = undefined;
      doc.avisoMessage = undefined;
      doc.estadoServicio = undefined;
    }

    const payload: BatchDocumentItem[] = docs.map(d => ({
      clientId: d.id,
      document: { fileName: d.file.name, contentType: d.file.type, fileContent: d.originalBase64 },
      confirmedRedactions: marcasEnviadas
    }));

    this.apiService.submitBatch(opciones, payload).subscribe({
      next: (res: SubmitBatchResponse) => {
        for (const doc of docs.filter(d => this.esVigente(d, envio))) {

          const ticketId = res.tickets?.find(t => t.clientId === doc.id)?.serverTicketId;
          if (!ticketId) {
            this.marcarError(doc, MENSAJE_NO_ACEPTADO);
            continue;
          }
          doc.serverTicketId = ticketId;
          doc.status = preview ? DocState.POLLING_PREVIEW : DocState.POLLING_FINAL;
          this.seguir(doc, envio, preview, marcasEnviadas, cadena);
        }
      },
      error: err => {
        const vigentes = docs.filter(d => this.esVigente(d, envio));
        if (esSaturado(err)) {
          this.esperarTurno(vigentes, envio, opciones, marcasEnviadas, cadena);
          return;
        }
        vigentes.forEach(d => this.marcarError(d, mensajeError(err, this.config.maxUploadSizeMB())));
      }
    });
  }

  private seguir(doc: DocumentItemUI, envio: number, preview: boolean, marcas?: RedactionItem[],
                 cadena?: Cadena): void {
    const ticketId = doc.serverTicketId!;
    const sub = esperarResultado(this.apiService, ticketId).subscribe({                  
      next: res => {
        if (!this.esVigente(doc, envio)) return;
        const estado = res.taskStatus?.processState;
        if (estado !== ProcessStateEnum.COMPLETED || !res.processedDocument) {
          this.marcarError(doc, mensajeEstado(estado, doc.serverTicketId));
          return;
        }
        const aviso = avisoDe(res);

        // Tras un análisis completo, se reaplican decisiones y zonas de una página.
        // Si cambia algo, se reconstruye y el documento sigue «en proceso» hasta el final.
        if (cadena?.fusion && !marcas) {
          const motor = res.redactionResults || [];
          const fusionadas = this.fusionar(motor, cadena.fusion);
          if (!mismasMarcas(fusionadas, motor)) {
            this.enviar([doc], { ...doc.options, redactionPreview: true, reconstructionMode: true },
              fusionadas, { aviso, mensaje: mensajeFinDe(res) });
            return;
          }
        }

        doc.finalBase64 = res.processedDocument.fileContent;
        doc.descargado = false; // sin descargar
        // Tras una reconstrucción se guardan las marcas enviadas, con las decisiones del usuario;
        // tras una detección, las que devuelve el motor
        doc.censoredItems = marcas ?? (res.redactionResults || []);
        doc.avisoMessage = aviso ?? cadena?.aviso;
        // Tras una reconstrucción encadenada se muestra el resumen del análisis
        doc.resultMessage = cadena?.mensaje ?? mensajeFinDe(res);
        doc.status = preview ? DocState.REVIEW_READY : DocState.COMPLETED;
        this.terminar(doc);
      },
      error: err => {
        if (this.esVigente(doc, envio)) this.marcarError(doc, mensajeError(err));
      }
    });
    // Fase de getStatus en cada consulta; deja de escucharse junto con el seguimiento
    sub.add(this.apiService.estados$
      .pipe(filter(e => e.ticketId === ticketId))
      .subscribe(e => {
        if (this.esVigente(doc, envio)) doc.estadoServicio = faseDe(e.estado);
      }));
    if (this.esVigente(doc, envio)) this.seguimientos.set(doc.id, sub);
  }

  /** Marcas del motor con las decisiones del panel y las zonas de una página reaplicadas */
  private fusionar(motor: RedactionItem[], fusion: Fusion): RedactionItem[] {
    const marcas = conservarDecisiones(structuredClone(motor), fusion.previas, fusion.antes, fusion.despues);
    return marcasConZonasPagina(marcas, fusion.previas, fusion.despues.zonasPagina ?? []);
  }

  private marcarError(doc: DocumentItemUI, mensaje: string): void {
    doc.status = DocState.ERROR;
    doc.resultMessage = mensaje;
    this.terminar(doc);
  }

  private terminar(doc: DocumentItemUI): void {
    doc.estadoServicio = undefined;
    this.seguimientos.delete(doc.id);
    this.envios.delete(doc.id);
    this.terminado.next(doc);
    this.liberarAlta(doc.id);
  }

  /** Falso si el documento se ha descartado o reenviado desde este envío */
  private esVigente(doc: DocumentItemUI, envio: number): boolean {
    return this.envios.get(doc.id) === envio;
  }

  private detener(id: string): void {
    this.seguimientos.get(id)?.unsubscribe();
    this.seguimientos.delete(id);
    this.envios.delete(id);
    // Descartado o reenviado: sale de la espera. La plaza la devuelven terminar() y descartar()
    this.colaAltas = this.colaAltas.filter(a => a.doc.id !== id);
  }
}

/** Lee el archivo y devuelve su contenido en base64, sin el prefijo data: */
function leerBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = typeof reader.result === 'string' ? reader.result : '';
      const coma = dataUrl.indexOf(',');
      if (coma >= 0) {
        resolve(dataUrl.slice(coma + 1));
      } else {
        reject(new Error('Lectura vacía'));
      }
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

/** Fase intermedia de getStatus que se muestra en la bandeja */
function faseDe(estado?: ProcessStateEnum): FaseServicio | undefined {
  switch (estado) {
    case ProcessStateEnum.QUEUED: return 'EN_COLA';
    case ProcessStateEnum.PROCESSING: return 'PROCESANDO';
    default: return undefined; // COMPLETED y los errores los resuelve seguir()
  }
}

/** Resumen del servicio al terminar bien (taskStatus.message) */
function mensajeFinDe(res: GetStatusResponse): string | undefined {
  return res.taskStatus?.message?.trim() || undefined;
}