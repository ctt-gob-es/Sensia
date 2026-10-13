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


import { Injectable } from '@angular/core';
import { DocumentItemUI } from '../interfaces/types';


@Injectable({
  providedIn: 'root'
})
export class DocumentStateService {
  private documentoEnRevision: DocumentItemUI | null = null;
  private soloLectura = false;

  setDocumento(doc: DocumentItemUI, soloLectura = false): void {
    this.documentoEnRevision = doc;
  }

  getDocumento(): DocumentItemUI | null {
    return this.documentoEnRevision;
  }

  /** La revisión se abre solo para consultar: sin cambios ni nuevos análisis */
  esSoloLectura(): boolean {
    return this.soloLectura;
  }


  limpiar(): void {
    this.documentoEnRevision = null;
  }
}