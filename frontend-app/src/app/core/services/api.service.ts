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


import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, tap } from 'rxjs';
import { EnvironmentService } from './environment.service';
import {
  ProcessingOptions,
  BatchDocumentItem,
  SubmitBatchResponse,
  GetStatusResponse,
  ProcessStateEnum
} from '../interfaces/types';

/** Estado de un ticket en una consulta de getStatus */
export interface EstadoTicket {
  ticketId: string;
  estado?: ProcessStateEnum;
}

@Injectable({
  providedIn: 'root'
})
export class ApiService {

  private envConfig = inject(EnvironmentService);
  private http = inject(HttpClient);

  private estados = new Subject<EstadoTicket>();
  /** Cada respuesta de getStatus, también las intermedias (en cola, procesando) */
  readonly estados$ = this.estados.asObservable();


  private get apiUrl() {
    return `${this.envConfig.apiUrl()}/sensiaws`;
  }

  submitBatch(options: ProcessingOptions, documents: BatchDocumentItem[]): Observable<SubmitBatchResponse> {
    const payload = {
      options,
      documents
    };
    return this.http.post<SubmitBatchResponse>(`${this.apiUrl}/submit-batch`, payload);
  }

  checkStatus(ticketId: string): Observable<GetStatusResponse> {
      return this.http.get<GetStatusResponse>(`${this.apiUrl}/status/${ticketId}`).pipe(
      tap(res => this.estados.next({ ticketId, estado: res.taskStatus?.processState }))
    );
  }


}
