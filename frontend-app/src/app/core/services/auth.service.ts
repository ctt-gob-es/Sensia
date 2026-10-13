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


import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { EnvironmentService } from './environment.service';


@Injectable({
  providedIn: 'root'
})
export class AuthService {

  private envConfig = inject(EnvironmentService);
  private http = inject(HttpClient);


  private get apiUrl() {
    return `${this.envConfig.apiUrl()}/auth`;
  }

  private authState = signal<{ token: string | null, user: any | null, role: any | null }>({
    token: sessionStorage.getItem('authToken'),
    user: sessionStorage.getItem('currentUser'),
    role: sessionStorage.getItem('currentRole')
  });

  public currentUser = computed(() => this.authState().user);
  public isLoggedIn = computed(() => !!this.authState().token);


  login(credentials: { email: any, password: any, altcha: any }): Observable<any> {
    return this.http.post(`${this.apiUrl}/login`, {
      username: credentials.email,
      password: credentials.password,
      altcha: credentials.altcha
    }).pipe(
      tap((res: any) => {
        if (res.auth && res.token) {
          sessionStorage.setItem('authToken', res.token);
          sessionStorage.setItem('currentUser', res.user);
          sessionStorage.setItem('currentRole', res.role);

          this.authState.set({ token: res.token, user: res.user, role: res.role });
        }
      })
    );
  }


  resetPassword(email: any, token: string, newPassword: string, altcha: any): Observable<any> {
    return this.http.post(`${this.apiUrl}/reset-password`, { 
      username: email, 
      tempPassword: token, 
      newPassword: newPassword,
      altcha: altcha 
    });
  }
  register(email: any, altcha: any): Observable<any> {
    return this.http.post(`${this.apiUrl}/register`, {
      username: email,
      altcha: altcha
    });
  }

  logout(): void {
    sessionStorage.clear();

    this.authState.set({ token: null, user: null, role: null });
  }


  hasToken(): boolean {
    return !!sessionStorage.getItem('authToken');
  }

  getToken(): string | null {
    return sessionStorage.getItem('authToken');
  }

  getCurrentUser(): { name: string; avatar: string } | null {
    const user = sessionStorage.getItem('currentUser');
    // El login guarda el nombre de usuario como texto, no como JSON
    return user ? { name: user, avatar: '' } : null;
  }



  checkServerStatus(): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/status`).pipe(
      catchError((err) => of(
        // Mantenimiento técnico: el servidor responde 503 con su propio estado; se respeta
        err?.error?.status ? err.error : {
          status: 'maintenance',
          type: 'hard',
          message: 'No se puede conectar con el servidor. Inténtelo más tarde.',
          features: { maintenanceMode: true }
        }
      ))
    );
  }



  isAdmin(): boolean {
    const role = sessionStorage.getItem('currentRole');
    if (role) {

      return role === 'admin';
    }
    return false;
  }


  forgotPassword(email: any, altcha: any): Observable<any> {
    return this.http.post(`${this.apiUrl}/forgot-password-otp`, {
      username: email,
      altcha: altcha
    });
  }

}
