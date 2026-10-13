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
import { Observable } from 'rxjs';
import { EnvironmentService } from './environment.service';
import { ProcessingOptions, RedactionTemplate } from '../interfaces/types';


@Injectable({ providedIn: 'root' })
export class TemplateService {

  private envConfig = inject(EnvironmentService);
  private http = inject(HttpClient);

  private get apiUrl() {
    return `${this.envConfig.apiUrl()}/templates`;
  }

  getTemplates(): Observable<RedactionTemplate[]> {
    return this.http.get<RedactionTemplate[]>(this.apiUrl);
  }

  saveTemplate(name: string, options: ProcessingOptions, isPublic = false): Observable<{ message: string, template: RedactionTemplate }> {
    return this.http.post<{ message: string, template: RedactionTemplate }>(this.apiUrl, { name, options, isPublic });
  }

  deleteTemplate(id: string | number): Observable<any> {
    return this.http.delete(`${this.apiUrl}/${id}`);
  }

  updateTemplate(id: number, options: any): Observable<any> {
    return this.http.put(`${this.apiUrl}/${id}`, { options });
  }

}