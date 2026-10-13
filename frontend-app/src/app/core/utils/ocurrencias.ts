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
import { TIPO_ZONA_FIJA } from './marcas';
import { normalizarFrase } from './seleccion-visor';

/*
 * Apariciones del mismo elemento en el documento, para decidir sobre todas a la vez desde el
 * panel de detecciones.
 *
 * Mismo elemento: mismo tipo y mismo texto (sin distinguir mayúsculas ni espacios) o, en las
 * censuras fijas, misma zona. Rostros, imágenes y QR no tienen una identidad fiable entre
 * páginas: cada uno va por su cuenta.
 */

/** La marca está censurada (interruptor del panel activado) */
export function estaCensurada(marca: RedactionItem): boolean {
  return marca.action === ActionStateEnum.REDACTED || marca.action === ActionStateEnum.REMOVED;
}

/** Censura o deja sin censurar una marca, igual que el interruptor del panel */
export function fijarCensura(marca: RedactionItem, censurar: boolean): void {
  marca.action = censurar ? ActionStateEnum.REDACTED : ActionStateEnum.WHITELISTED;
  marca.apply = censurar;
}

/** Coordenadas de una marca (RedactionItemDTO del backend) */
export function zonaDe(marca: RedactionItem): Coordinate | undefined {
  return (marca as unknown as { coordinates?: Coordinate }).coordinates;
}

/** Zona en texto «x, y, w, h», como en el campo «Censuras fijas» */
export function textoZona(zona: Coordinate): string {
  const numero = (valor: number) => String(Math.round(valor * 100) / 100);
  return `${numero(zona.x)}, ${numero(zona.y)}, ${numero(zona.w)}, ${numero(zona.h)}`;
}

/**
 * Identidad de una marca para reconocer el mismo elemento en otras páginas.
 * Undefined si no hay forma fiable o si la marca no se decide en el panel.
 */
export function claveElemento(marca: RedactionItem): string | undefined {
  if (marca.action !== ActionStateEnum.REDACTED && marca.action !== ActionStateEnum.WHITELISTED) {
    return undefined;
  }
  const tipo = String(marca.detectionType);
  const texto = normalizarFrase(marca.text ?? '').toLocaleLowerCase('es');
  if (texto) return `${tipo}|texto|${texto}`;
  const zona = zonaDe(marca);
  if (tipo === TIPO_ZONA_FIJA && zona) return `${tipo}|zona|${textoZona(zona)}`;
  return undefined;
}

/**
 * Agrupa las apariciones del mismo elemento. Cada marca apunta a su grupo
 * las que no tienen identidad quedan en un grupo de una.
 */
export function agruparApariciones(marcas: RedactionItem[]): Map<RedactionItem, RedactionItem[]> {
  const porClave = new Map<string, RedactionItem[]>();
  const resultado = new Map<RedactionItem, RedactionItem[]>();
  for (const marca of marcas) {
    const clave = claveElemento(marca);
    if (!clave) {
      resultado.set(marca, [marca]);
      continue;
    }
    let grupo = porClave.get(clave);
    if (!grupo) {
      grupo = [];
      porClave.set(clave, grupo);
    }
    grupo.push(marca);
    resultado.set(marca, grupo);
  }
  return resultado;
}

function paginasDe(grupo: RedactionItem[]): number {
  return new Set(grupo.map(m => m.page ?? 1)).size;
}

export function textoApariciones(grupo: RedactionItem[]): string {
  if (grupo.length < 2) return '';
  const paginas = paginasDe(grupo);
  return paginas > 1 ? `En ${paginas} páginas` : `${grupo.length} veces`;
}

export function textoTodas(grupo: RedactionItem[]): string {
  const paginas = paginasDe(grupo);
  return paginas > 1 ? `en las ${paginas} páginas` : `en sus ${grupo.length} apariciones`;
}