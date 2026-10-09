import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ApiService } from './api.service';

export type ModoTelegram = 'real' | 'test';

export interface ConfiguracionTelegram {
  modo: ModoTelegram;
  test_configurado: boolean;
  canal_activo: string | null;
  grupo_activo: string | null;
  canal_real: string | null;
  grupo_real: string | null;
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
    this.api.get<ConfiguracionTelegram>('/api/config').subscribe({
      next: (c) => this.config.next(c),
      error: () => this.config.next(null)
    });
  }

  cambiarModo(modo: ModoTelegram): Observable<ConfiguracionTelegram> {
    return this.api.post<ConfiguracionTelegram>('/api/config/modo', { modo });
  }

  aplicar(actual: ConfiguracionTelegram): void {
    this.config.next(actual);
  }
}