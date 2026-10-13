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

import { Coordinate } from '../interfaces/types';
import { ZonaPagina } from './opciones-procesamiento';

/** Longitud mínima y máxima de una frase seleccionada para la lista negra */
export const FRASE_MIN = 3;
export const FRASE_MAX = 200;

/** Lado mínimo, en puntos PDF, de una zona marcada */
const LADO_MIN_PUNTOS = 2;
/** Distancia, en píxeles, a partir de la cual un gesto es un arrastre y no un clic */
const UMBRAL_ARRASTRE = 4;

export interface Punto {
  x: number;
  y: number;
}

/** Lo que se usa de la vista de página de pdf.js (PageViewport con scale 1: puntos PDF) */
export interface VistaPagina {
  width: number;
  height: number;
  convertToPdfPoint(x: number, y: number): number[];
}

/** Cambio hecho en el visor y todavía sin aplicar */
export type Pendiente =
  | { tipo: 'frase'; texto: string }
  | ({ tipo: 'zona' } & ZonaPagina);

// ---------------------------------------------------------------------------
// Frases
// ---------------------------------------------------------------------------

/** Una sola línea: saltos y espacios repetidos pasan a un espacio; sin caracteres invisibles */
export function normalizarFrase(texto: string): string {
  return texto.replace(/[\u200B\u00AD]/g, '').replace(/\s+/g, ' ').trim();
}

/** Motivo por el que la frase no sirve para la lista negra; undefined si es válida */
export function motivoFraseNoValida(frase: string): string | undefined {
  if (frase.length < FRASE_MIN) return `Selecciona al menos ${FRASE_MIN} caracteres.`;
  if (frase.length > FRASE_MAX) return `La selección es demasiado larga (máximo ${FRASE_MAX} caracteres).`;
  if (frase.includes(';')) {
    return 'La selección contiene «;», que separa las palabras de la lista. Selecciona una parte sin ese signo.';
  }
  return undefined;
}

export function mismaFrase(a: string, b: string): boolean {
  return normalizarFrase(a).toLocaleLowerCase('es') === normalizarFrase(b).toLocaleLowerCase('es');
}

export function recortar(texto: string, maximo: number): string {
  return texto.length > maximo ? texto.slice(0, maximo).trimEnd() + '…' : texto;
}

// ---------------------------------------------------------------------------
// Coordenadas: vista de página -> puntos PDF
// ---------------------------------------------------------------------------

function acotar(valor: number, minimo: number, maximo: number): number {
  return Math.min(Math.max(valor, minimo), maximo);
}

function redondear(valor: number): number {
  return Math.round(valor * 100) / 100;
}

/**
 * Zona en puntos PDF (origen abajo a la izquierda) entre dos esquinas opuestas dadas en
 * unidades de la vista (scale 1). La vista de pdf.js ya incluye la rotación de la página.
 * Devuelve undefined si es demasiado pequeña.
 */
export function zonaDesdePuntos(vista: VistaPagina, a: Punto, b: Punto): Coordinate | undefined {
  const [x1, y1] = vista.convertToPdfPoint(a.x, a.y);
  const [x2, y2] = vista.convertToPdfPoint(b.x, b.y);
  const zona: Coordinate = {
    x: redondear(Math.min(x1, x2)),
    y: redondear(Math.min(y1, y2)),
    w: redondear(Math.abs(x2 - x1)),
    h: redondear(Math.abs(y2 - y1))
  };
  return zona.w >= LADO_MIN_PUNTOS && zona.h >= LADO_MIN_PUNTOS ? zona : undefined;
}

// ---------------------------------------------------------------------------
// Captura de zonas sobre el visor
// ---------------------------------------------------------------------------

/** Punto de una página: fracción (0–1) de su ancho y alto, y posición del puntero en pantalla */
interface Esquina {
  pagina: number;
  div: HTMLElement;
  fx: number;
  fy: number;
  cx: number;
  cy: number;
}

/**
 * Captura de zonas directamente sobre el contenedor del visor, sin depender de eventos de la
 * librería. Los eventos se interceptan en fase de captura (por encima de la capa de texto) y
 * la página se identifica con el contenedor `.page` de pdf.js y su `data-page-number`.
 *
 * Las posiciones se guardan como fracción de la página, así que no dependen del zoom ni del
 * desplazamiento. Solo al terminar se convierten a puntos PDF con la vista de la página.
 *
 * Se admite arrastrar o marcar dos esquinas opuestas con dos clics.
 */
export class CapturaZonas {
  private activa = false;
  private inicio?: Esquina;             // puntero pulsado
  private esquina?: Esquina;            // primer clic, a la espera del segundo
  private arrastrado = false;
  private trazo?: HTMLElement;          // rectángulo que se está dibujando
  private vistas = new Map<number, Promise<VistaPagina | undefined>>();
  private readonly escuchas: [string, (e: PointerEvent) => void][];

  constructor(
    private readonly contenedor: HTMLElement,
    private readonly obtenerVista: (pagina: number) => Promise<VistaPagina | undefined>,
    private readonly alCrear: (pagina: number, zona: Coordinate) => void,
    private readonly alAviso: (mensaje: string) => void
  ) {
    this.escuchas = [
      ['pointerdown', e => this.alPulsar(e)],
      ['pointermove', e => this.alMover(e)],
      ['pointerup', e => this.alSoltar(e)],
      ['pointercancel', () => this.cancelar()],
      ['click', e => this.alHacerClic(e)]
    ];
    for (const [tipo, escucha] of this.escuchas) {
      contenedor.addEventListener(tipo, escucha as EventListener, true);
    }
  }

  destruir(): void {
    this.activar(false);
    for (const [tipo, escucha] of this.escuchas) {
      this.contenedor.removeEventListener(tipo, escucha as EventListener, true);
    }
  }

  /** Olvida los tamaños de página leídos (al cargar otro documento) */
  vaciarVistas(): void {
    this.vistas.clear();
  }

  /** Activa o desactiva el modo «marcar zona» */
  activar(valor: boolean): void {
    this.activa = valor;
    if (!valor) this.cancelar();
    this.contenedor.style.cursor = valor ? 'crosshair' : '';
    this.contenedor.style.touchAction = valor ? 'none' : '';
  }

  /** Cancela el trazo en curso. Devuelve true si había algo que cancelar. */
  cancelar(): boolean {
    const habiaAlgo = !!(this.inicio || this.esquina || this.trazo);
    this.inicio = undefined;
    this.esquina = undefined;
    this.arrastrado = false;
    this.trazo?.remove();
    this.trazo = undefined;
    return habiaAlgo;
  }

  // --- Gestos ---------------------------------------------------------------

  private alPulsar(e: PointerEvent): void {
    if (!this.activa || e.button !== 0) return;
    const punto = this.localizar(e);
    if (!punto) return;
    e.preventDefault();   // no empieza una selección de texto
    e.stopPropagation();
    this.inicio = punto;
    this.arrastrado = false;
  }

  private alMover(e: PointerEvent): void {
    if (!this.activa) return;
    const referencia = this.inicio ?? this.esquina;
    if (!referencia) return;
    const punto = this.localizar(e, referencia.div);
    if (!punto) return;

    if (this.inicio && !this.arrastrado
        && Math.hypot(punto.cx - this.inicio.cx, punto.cy - this.inicio.cy) > UMBRAL_ARRASTRE) {
      this.arrastrado = true;
    }
    if (this.inicio && this.arrastrado) this.dibujar(this.inicio, punto);
    else if (!this.inicio && this.esquina) this.dibujar(this.esquina, punto); // entre los dos clics
  }

  private alSoltar(e: PointerEvent): void {
    if (!this.activa || !this.inicio) return;
    const desde = this.inicio;
    const punto = this.localizar(e, desde.div);
    this.inicio = undefined;
    if (!punto) { this.cancelar(); return; }

    if (this.arrastrado) {
      this.arrastrado = false;
      void this.terminar(desde, punto);
    } else if (this.esquina && this.esquina.pagina === desde.pagina) {
      void this.terminar(this.esquina, punto);
    } else {
      this.cancelar();
      this.esquina = punto;   // primer clic (o cambio de página): empieza de nuevo
      this.dibujar(punto, punto);
    }
  }

  /** En modo zona, un clic no debe abrir enlaces ni activar nada de la página */
  private alHacerClic(e: Event): void {
    if (!this.activa) return;
    e.preventDefault();
    e.stopPropagation();
  }

  private async terminar(a: Esquina, b: Esquina): Promise<void> {
    this.cancelar();
    const vista = await this.vistaDe(a.pagina);
    if (!vista) {
      this.alAviso('No se ha podido leer el tamaño de la página. Vuelve a intentarlo.');
      return;
    }
    const zona = zonaDesdePuntos(
      vista,
      { x: a.fx * vista.width, y: a.fy * vista.height },
      { x: b.fx * vista.width, y: b.fy * vista.height }
    );
    if (!zona) {
      this.alAviso('La zona es demasiado pequeña. Arrastra o marca dos esquinas más separadas.');
      return;
    }
    this.alCrear(a.pagina, zona);
  }

  // --- Auxiliares -----------------------------------------------------------

  private vistaDe(pagina: number): Promise<VistaPagina | undefined> {
    let vista = this.vistas.get(pagina);
    if (!vista) {
      vista = this.obtenerVista(pagina);
      this.vistas.set(pagina, vista);
      void vista.then(v => { if (!v) this.vistas.delete(pagina); });
    }
    return vista;
  }

  /**
   * Punto bajo el puntero, relativo a su página. Si se indica `forzada`, se mide contra esa
   * página aunque el puntero ya esté fuera de ella (para no perder el gesto al arrastrar).
   */
  private localizar(e: PointerEvent, forzada?: HTMLElement): Esquina | undefined {
    const destino = e.target instanceof Element ? e.target : null;
    const div = forzada ?? destino?.closest<HTMLElement>('.page') ?? undefined;
    if (!div || !this.contenedor.contains(div)) return undefined;
    const pagina = Number(div.dataset['pageNumber']);
    if (!Number.isFinite(pagina) || pagina < 1) return undefined;

    const caja = div.getBoundingClientRect();
    if (caja.width <= 0 || caja.height <= 0) return undefined;
    return {
      pagina,
      div,
      fx: acotar((e.clientX - caja.left) / caja.width, 0, 1),
      fy: acotar((e.clientY - caja.top) / caja.height, 0, 1),
      cx: e.clientX,
      cy: e.clientY
    };
  }

  /** Rectángulo provisional dentro de la página, en porcentaje */
  private dibujar(a: Esquina, b: Esquina): void {
    if (!this.trazo) {
      if (getComputedStyle(a.div).position === 'static') a.div.style.position = 'relative';
      const el = document.createElement('div');
      Object.assign(el.style, {
        position: 'absolute',
        boxSizing: 'border-box',
        border: '2px dashed #d93025',
        background: 'rgba(217, 48, 37, 0.12)',
        pointerEvents: 'none',
        zIndex: '20'
      });
      a.div.appendChild(el);
      this.trazo = el;
    }
    const el = this.trazo;
    el.style.left = `${Math.min(a.fx, b.fx) * 100}%`;
    el.style.top = `${Math.min(a.fy, b.fy) * 100}%`;
    el.style.width = `${Math.max(Math.abs(b.fx - a.fx) * 100, 0.4)}%`;
    el.style.height = `${Math.max(Math.abs(b.fy - a.fy) * 100, 0.4)}%`;
  }
}