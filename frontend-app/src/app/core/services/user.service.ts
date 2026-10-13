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

@Injectable({ providedIn: 'root' })
export class UserService {

  private envConfig = inject(EnvironmentService);

  private http = inject(HttpClient);


  private get apiUrl() {
    return `${this.envConfig.apiUrl()}/users`;
  }

  getUsers(): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/users`);
  }

  createUser(user: any): Observable<any> {
    return this.http.post(`${this.apiUrl}/users`, user);
  }

  updateUser(id: number, user: any): Observable<any> {
    return this.http.put(`${this.apiUrl}/users/${id}`, user);
  }

  deleteUser(id: number): Observable<any> {
    return this.http.delete(`${this.apiUrl}/users/${id}`);
  }

  toggleBlock(id: number): Observable<any> {
    return this.http.put(`${this.apiUrl}/users/${id}/block`, {});
  }


  updateSystemConfig(payload: { [x: string]: boolean; }) {
    return this.http.put(`${this.apiUrl}/config`, payload);
  }


  updatePassword(payload: { currentPassword: string; newPassword: string }): Observable<any> {
    return this.http.put(`${this.apiUrl}/profile/password`, payload);
  }


  deleteMyAccount(): Observable<any> {
    return this.http.delete(`${this.apiUrl}/profile`);
  }

  getSettings(): Observable<any> {
    return this.http.get(`${this.apiUrl}/profile`);
  }

  getDefaultSettings(): Observable<any> {
    return this.http.get(`${this.apiUrl}/profile/defaults`);
  }


  updateSettings(settings: any): Observable<any> {
    return this.http.put(`${this.apiUrl}/profile`, { settings });
  }

}