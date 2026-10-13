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

import { ActionStateEnum, Coordinate, RedactionItem } from '../interfaces/types';
import {
  AjustesDocumento,
  ZonaPagina,
  contieneZona,
  contieneZonaPagina,
  mismaZona,
  mismaZonaPagina
} from './opciones-procesamiento';

/** Tipo de detección de las censuras fijas (DetectionType.FIXED_ZONE del backend) */
export const TIPO_ZONA_FIJA: string = 'FIXED_ZONE';
/** Tipo de detección de las imágenes (DetectionType.IMAGE del backend) */
export const TIPO_IMAGEN: string = 'IMAGE';
/** Descripción de las censuras marcadas en el visor: las distingue de las fijas de todas las páginas */
export const DESCRIPCION_ZONA_MANUAL = 'Zona marcada en el visor';

/**
 * Marcas resultantes de aplicar un cambio de ajustes sobre las detecciones actuales, sin volver
 * a detectar. Conserva las decisiones del usuario en el panel. Solo cubre los cambios que no
 * exigen análisis completo (ver motivosAnalisisCompleto).
 *
 * @param paginas número de páginas del documento: las censuras fijas se aplican en todas
 */
export function marcasConAjustes(marcas: RedactionItem[], antes: AjustesDocumento,
                                 despues: AjustesDocumento, paginas: number): RedactionItem[] {
  let resultado: RedactionItem[] = structuredClone(marcas);

  // Censuras fijas quitadas: desaparecen sus marcas en todas las páginas
  // (las marcadas en el visor se tratan aparte)
  const zonasQuitadas = antes.zonasFijas.filter(z => !contieneZona(despues.zonasFijas, z));
  resultado = resultado.filter(m => {
    const zona = zonaDe(m);
    return !(esZonaFija(m) && !esZonaManual(m) && zona && contieneZona(zonasQuitadas, zona));
  });

  // Censuras fijas nuevas: una marca censurada en cada página
  const zonasNuevas = despues.zonasFijas.filter(z => !contieneZona(antes.zonasFijas, z));
  for (const zona of zonasNuevas) {
    for (let pagina = 1; pagina <= paginas; pagina++) {
      resultado.push(marcaZonaFija(zona, pagina));
    }
  }

  // Zonas de una página quitadas: desaparece su marca
  const paginaQuitadas = antes.zonasPagina.filter(z => !contieneZonaPagina(despues.zonasPagina, z));
  resultado = resultado.filter(m => !paginaQuitadas.some(z => esMarcaDeZonaPagina(m, z)));

  // Zonas de una página nuevas: una marca censurada en su página
  const paginaNuevas = despues.zonasPagina.filter(z => !contieneZonaPagina(antes.zonasPagina, z));
  for (const z of paginaNuevas) {
    if (!resultado.some(m => esMarcaDeZonaPagina(m, z))) {
      resultado.push(marcaZonaManual(z.zona, z.pagina));
    }
  }

  // Lista blanca: lo añadido deja de censurarse y lo quitado vuelve a censurarse.
  // Coincidencia del texto completo de la marca, sin distinguir mayúsculas.
  const blancasAntes = new Set(antes.permitidas.map(normalizar));
  const blancasDespues = new Set(despues.permitidas.map(normalizar));
  for (const marca of resultado) {
    const texto = normalizar(marca.text ?? '');
    if (!texto) continue;
    if (blancasDespues.has(texto) && !blancasAntes.has(texto)) {
      permitir(marca);
    } else if (blancasAntes.has(texto) && !blancasDespues.has(texto)
               && marca.action === ActionStateEnum.WHITELISTED) {
      censurar(marca);
    }
  }

  // Dejar de eliminar imágenes: las imágenes detectadas se conservan
  if (antes.eliminarImagenes && !despues.eliminarImagenes) {
    resultado.filter(m => String(m.detectionType) === TIPO_IMAGEN).forEach(permitir);
  }

  return resultado;
}

/**
 * Traslada las decisiones del usuario (censurar / no censurar) de las marcas anteriores a las
 * que acaba de devolver el motor tras un análisis completo.
 *
 * Emparejado: mismo tipo, página y texto; entre varias candidatas se prefiere la de la misma
 * zona. Las marcas sin texto (rostros, QR, imágenes, censuras fijas) solo se emparejan por zona.
 * No se trasladan las decisiones sobre textos de la lista blanca ni sobre frases que acaban de
 * entrar en la lista negra: lo último que pidió el usuario manda sobre la decisión antigua.
 * Las marcas del visor no se emparejan aquí (ver marcasConZonasPagina).
 */
export function conservarDecisiones(nuevas: RedactionItem[], previas: RedactionItem[],
                                    antes: AjustesDocumento, despues: AjustesDocumento): RedactionItem[] {
  const resultado: RedactionItem[] = structuredClone(nuevas);
  const permitidas = new Set(despues.permitidas.map(normalizar));
  const bloqueadasAntes = new Set(antes.bloqueadas.map(normalizar));
  const bloqueadasNuevas = new Set(despues.bloqueadas.map(normalizar).filter(p => !bloqueadasAntes.has(p)));

  const libres = previas.filter(m => esDecidible(m) && !esZonaManual(m));
  const usadas = new Set<RedactionItem>();

  for (const marca of resultado) {
    if (!esDecidible(marca)) continue;
    const texto = normalizar(marca.text ?? '');
    if (texto && (permitidas.has(texto) || bloqueadasNuevas.has(texto))) continue;

    const previa = emparejar(marca, texto, libres, usadas);
    if (!previa) continue;
    usadas.add(previa);
    marca.action = previa.action;
    marca.apply = previa.apply;
  }
  return resultado;
}

/**
 * Añade una marca por cada zona de una página que aún no esté en las marcas. Si el usuario ya
 * tenía esa marca en el panel, se reutiliza con su decisión; si no, nace censurada.
 */
export function marcasConZonasPagina(marcas: RedactionItem[], previas: RedactionItem[],
                                     zonasPagina: ZonaPagina[]): RedactionItem[] {
  const resultado = [...marcas];
  for (const z of zonasPagina) {
    if (resultado.some(m => esMarcaDeZonaPagina(m, z))) continue;
    const previa = previas.find(m => esMarcaDeZonaPagina(m, z));
    resultado.push(previa ? structuredClone(previa) : marcaZonaManual(z.zona, z.pagina));
  }
  return resultado;
}

/** Mismas marcas, en el mismo orden y con las mismas decisiones */
export function mismasMarcas(a: RedactionItem[], b: RedactionItem[]): boolean {
  return a.length === b.length && a.every((m, i) => {
    const otra = b[i];
    return String(m.detectionType) === String(otra.detectionType)
      && m.action === otra.action
      && !!m.apply === !!otra.apply
      && (m.page ?? 1) === (otra.page ?? 1)
      && (m.text ?? '') === (otra.text ?? '')
      && mismaZona(zonaDe(m), zonaDe(otra));
  });
}

/**
 * Marca de censura fija para una página. Campos según RedactionItemDTO del backend
 * (coordinates, detectionType, action, page); apply acompaña a action, como en el panel.
 */
export function marcaZonaFija(zona: Coordinate, pagina: number, descripcion?: string): RedactionItem {
  return {
    detectionType: TIPO_ZONA_FIJA,
    action: ActionStateEnum.REDACTED,
    apply: true,
    page: pagina,
    description: descripcion,
    coordinates: { x: zona.x, y: zona.y, w: zona.w, h: zona.h }
  } as unknown as RedactionItem;
}

/** Marca de una censura fija de una sola página, marcada en el visor */
export function marcaZonaManual(zona: Coordinate, pagina: number): RedactionItem {
  return marcaZonaFija(zona, pagina, DESCRIPCION_ZONA_MANUAL);
}

export function esZonaManual(marca: RedactionItem): boolean {
  return esZonaFija(marca) && marca.description === DESCRIPCION_ZONA_MANUAL;
}

function esMarcaDeZonaPagina(marca: RedactionItem, z: ZonaPagina): boolean {
  const zona = zonaDe(marca);
  return esZonaManual(marca) && !!zona && mismaZonaPagina({ pagina: marca.page ?? 1, zona }, z);
}

function esZonaFija(marca: RedactionItem): boolean {
  return String(marca.detectionType) === TIPO_ZONA_FIJA;
}

/** Marcas sobre las que el usuario decide en el panel */
function esDecidible(marca: RedactionItem): boolean {
  return marca.action === ActionStateEnum.REDACTED || marca.action === ActionStateEnum.WHITELISTED;
}

function emparejar(marca: RedactionItem, texto: string, libres: RedactionItem[],
                   usadas: Set<RedactionItem>): RedactionItem | undefined {
  const pagina = marca.page ?? 1;
  const candidatas = libres.filter(p => !usadas.has(p)
    && String(p.detectionType) === String(marca.detectionType)
    && (p.page ?? 1) === pagina
    && normalizar(p.text ?? '') === texto);
  const zona = zonaDe(marca);
  return candidatas.find(p => mismaZona(zonaDe(p), zona)) ?? (texto ? candidatas[0] : undefined);
}

function zonaDe(marca: RedactionItem): Coordinate | undefined {
  return (marca as unknown as { coordinates?: Coordinate }).coordinates;
}

function permitir(marca: RedactionItem): void {
  marca.action = ActionStateEnum.WHITELISTED;
  marca.apply = false;
}

function censurar(marca: RedactionItem): void {
  marca.action = ActionStateEnum.REDACTED;
  marca.apply = true;
}

function normalizar(texto: string): string {
  return texto.replace(/\s+/g, ' ').trim().toLocaleLowerCase('es');
}