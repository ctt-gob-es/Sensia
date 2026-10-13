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

import { Coordinate, ProcessingOptions } from '../interfaces/types';

// Cuatro números X, Y, W, H separados por comas
const ZONA = String.raw`\s*\d+(?:\.\d+)?\s*(?:,\s*\d+(?:\.\d+)?\s*){3}`;

/** Una zona: X, Y, W, H separados por comas */
export const PATRON_ZONA = new RegExp(`^${ZONA}$`);

/** Varias zonas separadas por «;» (se admite un «;» final) */
export const PATRON_ZONAS = new RegExp(`^${ZONA}(?:;${ZONA})*;?\\s*$`);

/** Opciones que se aplican cuando el usuario no ha guardado preferencias */
export const OPCIONES_POR_DEFECTO: ProcessingOptions = {
  documentOptions: {
    redactQRCodes: true,
    redactNames: true,
    removeImages: false,
    preserveLogo: true,
  },
  faceOptions: {
    maskFaces: true
  },
  redactionPreview: true
};

/** Censura fija de una sola página, marcada en el visor */
export interface ZonaPagina {
  pagina: number;
  zona: Coordinate;
}

/**
 * Ajustes propios de un documento o de una plantilla: lo que depende de cada tipo de documento.
 * Sustituyen a los mismos campos de las preferencias; el resto de opciones sale de las preferencias.
 */
export interface AjustesDocumento {
  /** Lista negra: censurar siempre */
  bloqueadas: string[];
  /** Lista blanca: no censurar nunca */
  permitidas: string[];
  /** Censuras fijas, en todas las páginas */
  zonasFijas: Coordinate[];
  /**
   * Censuras fijas de una sola página, marcadas en el visor. Solo viven en el documento: el motor
   * no tiene campo de página para ellas (se aplican como marcas) y no se guardan en plantillas.
   */
  zonasPagina: ZonaPagina[];
  /** Zona del logotipo */
  zonaLogo?: Coordinate;
  /** Eliminar las imágenes del documento */
  eliminarImagenes: boolean;
  /** Plantilla de la que se partió, si se eligió una (solo informativo) */
  plantillaId?: number | null;
}

export function ajustesVacios(): AjustesDocumento {
  return { bloqueadas: [], permitidas: [], zonasFijas: [], zonasPagina: [], eliminarImagenes: false };
}

// ---------------------------------------------------------------------------
// Preferencias, plantillas y opciones del motor
// ---------------------------------------------------------------------------

/**
 * Convierte las preferencias guardadas en backend-app a la forma que espera el motor.
 *
 * Preferencias guarda las listas de palabras como texto separado por «;» y las zonas como
 * [x, y, w, h]; el motor espera { words: [...] }, { x, y, w, h } y { coordinate: [...] }.
 * También acepta la forma del motor, por si ya viniera así.
 */
export function opcionesDesdePreferencias(preferencias: unknown): ProcessingOptions {
  if (!preferencias || typeof preferencias !== 'object') {
    return structuredClone(OPCIONES_POR_DEFECTO);
  }
  const opciones = preferencias as ProcessingOptions;
  return combinarOpciones(opciones, ajustesDesdeOpciones(opciones));
}

/**
 * Opciones con los ajustes indicados en lugar de los que traían.
 * Listas y zonas salen en la forma del motor; las vacías no se envían.
 * Las zonas de una página no se envían: el motor las recibe como marcas confirmadas.
 */
export function combinarOpciones(opciones: ProcessingOptions, ajustes: AjustesDocumento): ProcessingOptions {
  const resultado = structuredClone(opciones ?? {}) as any;
  const documento = resultado.documentOptions ?? {};
  resultado.documentOptions = documento;

  documento.blockedWords = ajustes.bloqueadas.length > 0 ? { words: [...ajustes.bloqueadas] } : undefined;
  documento.allowedWords = ajustes.permitidas.length > 0 ? { words: [...ajustes.permitidas] } : undefined;
  documento.fixedRedactionZones = ajustes.zonasFijas.length > 0
    ? { coordinate: ajustes.zonasFijas.map(z => ({ ...z })) }
    : undefined;
  documento.logoExclusionZone = ajustes.zonaLogo ? { ...ajustes.zonaLogo } : undefined;
  documento.removeImages = ajustes.eliminarImagenes;
  return resultado as ProcessingOptions;
}

/**
 * Ajustes contenidos en unas opciones: las de un documento, unas preferencias o una plantilla.
 * Los campos que las opciones no traen se toman de `base`; así una plantilla antigua o
 * incompleta completa lo que le falta con las opciones globales.
 * Las zonas de una página nunca vienen en unas opciones: salen siempre de `base`.
 */
export function ajustesDesdeOpciones(
  opciones: ProcessingOptions | undefined,
  base: AjustesDocumento = ajustesVacios()
): AjustesDocumento {
  const documento: Record<string, unknown> = (opciones?.documentOptions as any) ?? {};
  const trae = (campo: string) => Object.prototype.hasOwnProperty.call(documento, campo);

  return {
    bloqueadas: trae('blockedWords') ? aPalabras(documento['blockedWords']) : [...base.bloqueadas],
    permitidas: trae('allowedWords') ? aPalabras(documento['allowedWords']) : [...base.permitidas],
    zonasFijas: trae('fixedRedactionZones')
      ? aZonas(documento['fixedRedactionZones'])
      : base.zonasFijas.map(z => ({ ...z })),
    zonasPagina: base.zonasPagina.map(z => ({ pagina: z.pagina, zona: { ...z.zona } })),
    zonaLogo: trae('logoExclusionZone')
      ? aZona(documento['logoExclusionZone'])
      : (base.zonaLogo ? { ...base.zonaLogo } : undefined),
    eliminarImagenes: trae('removeImages') ? !!documento['removeImages'] : base.eliminarImagenes
  };
}

/**
 * Opciones con las que se guardan unos ajustes como plantilla. Se escriben todos los campos,
 * también los vacíos: en una plantilla, vacío significa «ninguno», no «el global».
 * Las zonas de una página no se guardan: pertenecen a un documento concreto.
 */
export function opcionesDesdeAjustes(ajustes: AjustesDocumento): ProcessingOptions {
  const documentOptions = {
    blockedWords: { words: [...ajustes.bloqueadas] },
    allowedWords: { words: [...ajustes.permitidas] },
    fixedRedactionZones: { coordinate: ajustes.zonasFijas.map(z => ({ ...z })) },
    logoExclusionZone: ajustes.zonaLogo ? { ...ajustes.zonaLogo } : null,
    removeImages: ajustes.eliminarImagenes
  };
  return { documentOptions } as unknown as ProcessingOptions;
}

// ---------------------------------------------------------------------------
// Comparación de ajustes
// ---------------------------------------------------------------------------

/** Los ajustes difieren en algún campo (la plantilla de partida no cuenta) */
export function ajustesDistintos(a: AjustesDocumento, b: AjustesDocumento): boolean {
  return !mismasPalabras(a.bloqueadas, b.bloqueadas)
    || !mismasPalabras(a.permitidas, b.permitidas)
    || !mismasZonas(a.zonasFijas, b.zonasFijas)
    || !mismasZonasPagina(a.zonasPagina, b.zonasPagina)
    || !mismaZona(a.zonaLogo, b.zonaLogo)
    || a.eliminarImagenes !== b.eliminarImagenes;
}

/**
 * Motivos por los que pasar de `antes` a `despues` exige volver a detectar.
 * Vacío si basta con reconstruir sobre las marcas actuales.
 */
export function motivosAnalisisCompleto(antes: AjustesDocumento, despues: AjustesDocumento): string[] {
  const motivos: string[] = [];
  if (!mismasPalabras(antes.bloqueadas, despues.bloqueadas)) motivos.push('cambia la lista negra');
  if (!antes.eliminarImagenes && despues.eliminarImagenes) motivos.push('se eliminan las imágenes');
  if (!mismaZona(antes.zonaLogo, despues.zonaLogo)) motivos.push('cambia la zona del logotipo');
  if (!mismasZonasPagina(antes.zonasPagina ?? [], despues.zonasPagina ?? [])) {
    motivos.push('cambian las zonas marcadas en el visor');
  }
  return motivos;
}

/** Misma zona, con medio punto de tolerancia por redondeos */
export function mismaZona(a?: Coordinate, b?: Coordinate): boolean {
  if (!a || !b) return !a && !b;
  const cerca = (m: number, n: number) => Math.abs(m - n) < 0.5;
  return cerca(a.x, b.x) && cerca(a.y, b.y) && cerca(a.w, b.w) && cerca(a.h, b.h);
}

export function contieneZona(zonas: Coordinate[], zona: Coordinate): boolean {
  return zonas.some(z => mismaZona(z, zona));
}

/** Misma zona en la misma página */
export function mismaZonaPagina(a: ZonaPagina, b: ZonaPagina): boolean {
  return a.pagina === b.pagina && mismaZona(a.zona, b.zona);
}

export function contieneZonaPagina(zonas: ZonaPagina[], zona: ZonaPagina): boolean {
  return zonas.some(z => mismaZonaPagina(z, zona));
}

function mismasPalabras(a: string[], b: string[]): boolean {
  const conjunto = new Set(a);
  return a.length === b.length && b.every(p => conjunto.has(p));
}

function mismasZonas(a: Coordinate[], b: Coordinate[]): boolean {
  return a.length === b.length && a.every(z => contieneZona(b, z));
}

function mismasZonasPagina(a: ZonaPagina[], b: ZonaPagina[]): boolean {
  return a.length === b.length && a.every(z => contieneZonaPagina(b, z));
}

// ---------------------------------------------------------------------------
// Listas de palabras
// ---------------------------------------------------------------------------

/** Palabras escritas como texto separado por «;», como array o como { words: [...] } */
export function aPalabras(valor: unknown): string[] {
  let lista: unknown[] = [];

  if (typeof valor === 'string') {
    lista = valor.split(';');
  } else if (Array.isArray(valor)) {
    lista = valor;
  } else if (valor && typeof valor === 'object' && Array.isArray((valor as any).words)) {
    lista = (valor as any).words;
  }

  return sinRepetir(lista
    .filter((p): p is string => typeof p === 'string')
    .map(p => p.trim())
    .filter(Boolean));
}

/** Palabras en texto «a; b; c» para un formulario */
export function palabrasATexto(valor: unknown): string {
  return aPalabras(valor).join('; ');
}

// ---------------------------------------------------------------------------
// Zonas
// ---------------------------------------------------------------------------

/**
 * Interpreta una zona escrita como [x, y, w, h], «x, y, w, h» o { x, y, w, h }.
 * Devuelve undefined si no son exactamente cuatro números.
 */
export function aZona(valor: unknown): Coordinate | undefined {
  let numeros: number[] = [];

  if (Array.isArray(valor)) {
    numeros = valor.map(Number);
  } else if (typeof valor === 'string') {
    if (!PATRON_ZONA.test(valor)) return undefined;
    numeros = valor.split(',').map(Number);
  } else if (valor && typeof valor === 'object') {
    const z = valor as Record<string, unknown>;
    numeros = [z['x'], z['y'], z['w'], z['h']].map(Number);
  }

  return numeros.length === 4 && numeros.every(Number.isFinite)
    ? { x: numeros[0], y: numeros[1], w: numeros[2], h: numeros[3] }
    : undefined;
}

/** Zona en texto «x, y, w, h» para un formulario; vacía si no es válida */
export function zonaATexto(valor: unknown): string {
  const zona = aZona(valor);
  return zona ? `${zona.x}, ${zona.y}, ${zona.w}, ${zona.h}` : '';
}

/** Zona como [x, y, w, h], la forma en que la guarda Preferencias; vacía si no es válida */
export function zonaAArray(valor: unknown): number[] {
  const zona = aZona(valor);
  return zona ? [zona.x, zona.y, zona.w, zona.h] : [];
}

/**
 * Zonas escritas como texto «x, y, w, h; …», como array de zonas (cada una en cualquiera
 * de las formas de aZona) o como { coordinate: [...] }. Las que no son válidas se descartan.
 */
export function aZonas(valor: unknown): Coordinate[] {
  let lista: unknown[] = [];

  if (typeof valor === 'string') {
    if (!PATRON_ZONAS.test(valor)) return [];
    lista = valor.split(';').filter(t => t.trim() !== '');
  } else if (Array.isArray(valor)) {
    // Un único [x, y, w, h] también es una lista de una zona
    lista = valor.every(n => typeof n === 'number') ? [valor] : valor;
  } else if (valor && typeof valor === 'object' && Array.isArray((valor as any).coordinate)) {
    lista = (valor as any).coordinate;
  }

  return zonasSinRepetir(lista.map(z => aZona(z)).filter((z): z is Coordinate => z !== undefined));
}

/** Zonas en texto «x, y, w, h; …» para un formulario */
export function zonasATexto(valor: unknown): string {
  return aZonas(valor).map(z => `${z.x}, ${z.y}, ${z.w}, ${z.h}`).join('; ');
}

/** Zonas como [[x, y, w, h], …], la forma en que las guarda Preferencias */
export function zonasAArrays(valor: unknown): number[][] {
  return aZonas(valor).map(z => [z.x, z.y, z.w, z.h]);
}

function sinRepetir(lista: string[]): string[] {
  return [...new Set(lista)];
}

function zonasSinRepetir(zonas: Coordinate[]): Coordinate[] {
  return zonas.filter((z, i) => zonas.findIndex(otra => mismaZona(otra, z)) === i);
}