import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { tap } from 'rxjs/operators';
import { ApiService } from './api.service';
import { Canal, DeteccionChat } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class CanalService {
  private cambios = new Subject<void>();
  /** Avisa a las vistas abiertas de que un destino cambió de nombre, tipo o existencia. */
  readonly cambios$: Observable<void> = this.cambios.asObservable();

  constructor(private api: ApiService) {}

  listar(): Observable<Canal[]> {
    return this.api.get<Canal[]>('/api/canales');
  }

  crear(nombre: string, chatId: string, tipo: 'real' | 'test', botId?: number | null): Observable<Canal> {
    return this.api.post<Canal>('/api/canales', { nombre, chat_id: chatId, tipo, bot_id: botId })
      .pipe(tap(() => this.cambios.next()));
  }

  actualizar(id: number, datos: Partial<Pick<Canal, 'nombre' | 'chat_id' | 'tipo' | 'activo' | 'bot_id' | 'chat_tipo'>>): Observable<Canal> {
    return this.api.put<Canal>(`/api/canales/${id}`, datos)
      .pipe(tap(() => this.cambios.next()));
  }

  detectar(chatId: string, botId?: number | null): Observable<DeteccionChat> {
    const q = botId ? `?chat_id=${encodeURIComponent(chatId)}&bot_id=${botId}` : `?chat_id=${encodeURIComponent(chatId)}`;
    return this.api.get<DeteccionChat>(`/api/canales/detectar${q}`);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/api/canales/${id}`)
      .pipe(tap(() => this.cambios.next()));
  }
}
