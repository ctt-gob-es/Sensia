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

import { HttpErrorResponse } from '@angular/common/http';
import { EMPTY, Observable, TimeoutError, catchError, exhaustMap, filter, interval, take, tap, throwError, timeout } from 'rxjs';
import { ApiService } from '../services/api.service';
import { GetStatusResponse, ProcessStateEnum, StatusEnum } from '../interfaces/types';

const INTERVALO_MS = 3000;
const MAX_FALLOS_SEGUIDOS = 5;
const MAX_ESPERA_MS = 20 * 60 * 1000;

/** Solo estos estados están en curso; cualquier otro, incluido uno desconocido, es final. */
const EN_CURSO = new Set<string>([ProcessStateEnum.QUEUED, ProcessStateEnum.PROCESSING]);

const MENSAJES: Record<string, string> = {
  INVALID_DOCUMENT_ERROR: 'El archivo está dañado, no es un PDF válido o está protegido con contraseña. Quite la protección y vuelva a subirlo.',
  NOT_FOUND: 'El resultado ya no está disponible. Vuelva a subir el documento.',
  HASH_MISMATCH_ERROR: 'El documento no coincide con el revisado. Vuelva a procesarlo.',
  AUTH_ERROR: 'Error de configuración del servicio. Contacte con el administrador.',
};
const GENERICO = 'No se ha podido procesar el documento. Si se repite, contacte con el administrador';

export function mensajeEstado(state?: string, ticketId?: string): string {
  return MENSAJES[state ?? ''] ?? (ticketId ? `${GENERICO} indicando la referencia ${ticketId}.` : `${GENERICO}.`);
}

export function mensajeError(err: unknown, maxMb?: number): string {
  if (err instanceof TimeoutError) return 'El procesamiento ha superado el tiempo máximo. Vuelva a intentarlo.';
  if (!(err instanceof HttpErrorResponse)) return mensajeEstado();
  switch (err.status) {
    case 0: return 'No se puede conectar con el servidor. Compruebe la conexión.';
    case 401: return 'La sesión ha caducado. Vuelva a iniciar sesión.';
    case 413: return `El archivo supera el tamaño máximo permitido${maxMb ? ` (${maxMb} MB)` : ''}.`;
    case 502: case 503: case 504: return 'El servicio está ocupado. Inténtelo más tarde.';
    default: return mensajeEstado();
  }
}

/**
 * El servidor no ha aceptado el envío porque está lleno, en total o para este usuario
 */
export function esSaturado(err: unknown): boolean {
  return err instanceof HttpErrorResponse && (err.status === 503 || err.status === 429);
}


export function avisoDe(res: GetStatusResponse): string | undefined {
  return res.status?.result === StatusEnum.WARNING
    ? 'El documento se ha procesado con incidencias. Revíselo con especial atención antes de publicarlo.'
    : undefined;}

/** Consulta hasta un estado final, sin solapar peticiones y reintentando los fallos transitorios. */
export function esperarResultado(api: ApiService, ticketId: string): Observable<GetStatusResponse> {
  let fallos = 0;
  return interval(INTERVALO_MS).pipe(
    exhaustMap(() => api.checkStatus(ticketId).pipe(
      tap(() => (fallos = 0)),
      catchError((err: HttpErrorResponse) =>
        [0, 502, 503, 504].includes(err.status) && ++fallos < MAX_FALLOS_SEGUIDOS ? EMPTY : throwError(() => err)))),
    filter(res => !EN_CURSO.has(res.taskStatus?.processState ?? '')),
    take(1),
    timeout({ first: MAX_ESPERA_MS }),
  );
}