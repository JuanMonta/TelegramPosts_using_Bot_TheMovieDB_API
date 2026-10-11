import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ApiService } from './api.service';

export type ModoTelegram = 'real' | 'test';

/** Estado básico que devuelven GET y POST /api/config/modo. */
export interface ConfiguracionResumen {
  modo: ModoTelegram;
  test_configurado: boolean;
  canal_activo: string | null;
  grupo_activo: string | null;
  canal_real: string | null;
  grupo_real: string | null;
}

/** Estado completo de GET /api/config (el token solo llega enmascarado). */
export interface ConfiguracionTelegram extends ConfiguracionResumen {
  bot_token: string | null;
  bot_token_configurado: boolean;
  canal_principal: string | null;
  canal_prueba: string | null;
  limite_caption: number;
  intervalo_cola_ms: number;
  colgada_minutos: number;
  /** true = config_app no se pudo descifrar con la clave maestra actual. */
  clave_invalida?: boolean;
}

/** Payload de POST /api/config: los campos ausentes o null conservan el valor actual. */
export interface ConfiguracionGuardado {
  bot_token?: string | null;
  canal_principal?: string | null;
  canal_prueba?: string | null;
  limite_caption?: number | null;
  intervalo_cola_ms?: number | null;
  colgada_minutos?: number | null;
}

@Injectable({ providedIn: 'root' })
export class ConfiguracionService {
  private config = new BehaviorSubject<ConfiguracionTelegram | null>(null);
  readonly config$: Observable<ConfiguracionTelegram | null> = this.config.asObservable();

  constructor(private api: ApiService) {}

  /** Modo actual (real/test) o null si aún no se cargó. */
  get modo(): ModoTelegram | null {
    return this.config.value?.modo ?? null;
  }

  /** ¿Está activo el modo pruebas? */
  get esTest(): boolean {
    return this.config.value?.modo === 'test';
  }

  get testConfigurado(): boolean {
    return this.config.value?.test_configurado ?? false;
  }

  esTest$(): Observable<boolean> {
    return this.config$.pipe(map((c) => c?.modo === 'test'));
  }

  cargar(): void {
    this.obtener().subscribe({
      next: (c) => this.config.next(c),
      error: () => this.config.next(null)
    });
  }

  /** Estado completo fresco (para el diálogo de configuración). */
  obtener(): Observable<ConfiguracionTelegram> {
    return this.api.get<ConfiguracionTelegram>('/api/config');
  }

  cambiarModo(modo: ModoTelegram): Observable<ConfiguracionResumen> {
    return this.api.post<ConfiguracionResumen>('/api/config/modo', { modo });
  }

  /** Guarda los campos de configuración (null = conservar). Devuelve el estado completo. */
  guardar(datos: ConfiguracionGuardado): Observable<ConfiguracionTelegram> {
    return this.api.post<ConfiguracionTelegram>('/api/config', datos);
  }

  /** Aplica parcialmente un estado (mezcla con el actual). */
  aplicar(parcial: Partial<ConfiguracionTelegram>): void {
    this.config.next({ ...(this.config.value ?? {}), ...parcial } as ConfiguracionTelegram);
  }
}
