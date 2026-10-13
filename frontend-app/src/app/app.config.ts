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

import { ApplicationConfig, provideZoneChangeDetection, isDevMode, provideAppInitializer, inject } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { provideServiceWorker } from '@angular/service-worker';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { tokenInterceptor } from './core/interceptors/token.interceptor';
import { EnvironmentService } from './core/services/environment.service';
import { catchError, from, of, switchMap, tap } from 'rxjs';
import { AppConfigService } from './core/services/app-config.service';
import { AuthService } from './core/services/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),

    provideRouter(routes),

    provideHttpClient(
      withInterceptors([tokenInterceptor])
    ),
    provideAppInitializer(() => {
      const enviromentService = inject(EnvironmentService);
      const appConfigService = inject(AppConfigService);
      const authService = inject(AuthService);

      return from(enviromentService.loadConfig()).pipe(
        switchMap(() => authService.checkServerStatus()),
         // Límite de subida que comunica el servidor; si no llega, se mantiene el valor por defecto
        tap((res: any) => appConfigService.fijarMaxUploadSizeMB(res?.features?.maxUploadSizeMB)),
        catchError(err => {
          console.error('Fallo en el arranque:', err);
          return of({ status: 'maintenance', message: 'Error de conexión' });
        })
      );
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000'
    })
  ]
};
