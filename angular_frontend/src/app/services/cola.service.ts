import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ColaItem } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class ColaService {
  constructor(private api: ApiService) {}

  listar(): Observable<ColaItem[]> {
    return this.api.get<ColaItem[]>('/api/cola');
  }

  /** Item activo (programado/en curso) de una publicación, o null. */
  activoDePublicacion(publicacionId: number): Observable<ColaItem | null> {
    return this.api.get<ColaItem | null>(`/api/cola/publicacion/${publicacionId}`);
  }

  programar(publicacionId: number, programadoPara: string, acoplamientoId?: number | null): Observable<ColaItem> {
    return this.api.post<ColaItem>('/api/cola', { publicacionId, programadoPara, acoplamientoId });
  }

  reprogramar(id: number, programadoPara: string): Observable<ColaItem> {
    return this.api.put<ColaItem>(`/api/cola/${id}/fecha`, { programadoPara });
  }

  cancelar(id: number): Observable<ColaItem> {
    return this.api.delete<ColaItem>(`/api/cola/${id}`);
  }

  /** Reintenta un item fallido o cancelado; vuelve a 'pendiente' y se publica en el próximo tick. */
  reintentar(id: number): Observable<ColaItem> {
    return this.api.post<ColaItem>(`/api/cola/${id}/reintentar`, {});
  }

  /** Borra un item terminado (fallido/cancelado) de la vista de la cola. */
  eliminar(id: number): Observable<{ borrado: boolean; id: number }> {
    return this.api.delete<{ borrado: boolean; id: number }>(`/api/cola/${id}/registro`);
  }

  /** Borra de una sola vez todos los items de los estados dados (purga de un apartado). */
  purgar(estados: string[]): Observable<{ borrados: number }> {
    return this.api.post<{ borrados: number }>('/api/cola/purgar', { estados });
  }
}