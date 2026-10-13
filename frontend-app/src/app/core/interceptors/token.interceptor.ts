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

import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';
export const tokenInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn) => {

  const token = sessionStorage.getItem('authToken');
  const router = inject(Router);
  const authService = inject(AuthService);
  let authReq = req;
  if (token) {
      authReq = req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      });
    
  }

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      // Los 401/403 de /auth/ (credenciales, captcha, código, cuenta bloqueada) los muestra cada pantalla
      const esAcceso = req.url.includes('/auth/');
      if (!esAcceso && (error.status === 401 || error.status === 403)) {
        authService.logout();
        router.navigate(['/login']);
        
        alert('Tu sesión ha expirado o no tienes permisos.');
      }
      return throwError(() => error);
    })
  );
};