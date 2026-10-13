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

import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AppConfigService {
  /** Tamaño máximo por documento. Lo comunica backend-app en /auth/status; 30 si no llega */
  private readonly _maxUploadSizeMB = signal(30);
  readonly maxUploadSizeMB = this._maxUploadSizeMB.asReadonly();

  fijarMaxUploadSizeMB(valor: unknown): void {
    const mb = Number(valor);
    if (Number.isFinite(mb) && mb > 0) this._maxUploadSizeMB.set(mb);
  }
}