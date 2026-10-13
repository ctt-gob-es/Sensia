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

import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { EnvSettings as EnvSettings } from '../interfaces/env.settings'; 
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request';
import { W3CTraceContextPropagator } from '@opentelemetry/core';

@Injectable({
  providedIn: 'root'
})
export class EnvironmentService {

  private http = inject(HttpClient);
  
  private configUrl = '/env.json';

  private _config = signal<EnvSettings | undefined>(undefined); 

  public readonly config = this._config.asReadonly();

  public readonly apiUrl = computed(() => {
    const currentConfig = this._config();
    if (!currentConfig) {
      console.error('🚨 [ERROR] Se intentó leer la URL antes de cargar la configuración');
      throw new Error('[ERROR] Se intentó leer la URL antes de cargar la configuración');   
    }
    return currentConfig.apiUrl; 
  });

  private initOpenTelemetry(apiUrl: string) {
    // Escapa caracteres y crea expresión regular
    const safeUrl = apiUrl.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const apiRegex = new RegExp(`^${safeUrl}.*`);

    const provider = new WebTracerProvider();
    provider.register({
      contextManager: new ZoneContextManager(),
      propagator: new W3CTraceContextPropagator() 
    });

    registerInstrumentations({
      instrumentations: [
        new FetchInstrumentation({
          propagateTraceHeaderCorsUrls: [apiRegex]
        }),
        new XMLHttpRequestInstrumentation({
          propagateTraceHeaderCorsUrls: [apiRegex]
        })
      ]
    });
  }

  
  async loadConfig(): Promise<void> {
    let data: EnvSettings;
    try {
      data = await firstValueFrom(this.http.get<EnvSettings>(this.configUrl));
    } catch (error) {
      console.error('🚨 [ERROR] No se pudo cargar la configuración (env.json):', error);
      throw new Error('[ERROR] No se pudo cargar la configuración (env.json): ' + error);
    }
    this._config.set(data);

    // La trazabilidad es opcional: si falla, se avisa y la aplicación arranca sin ella
    if (data.enableTracing) {
      try {
        this.initOpenTelemetry(data.apiUrl);
      } catch (error) {
        console.warn('⚠️ [OTel] No se pudo iniciar la trazabilidad; se continúa sin ella:', error);
      }
    }
  }
}