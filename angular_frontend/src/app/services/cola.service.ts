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

  programar(publicacionId: number, programadoPara: string): Observable<ColaItem> {
    return this.api.post<ColaItem>('/api/cola', { publicacionId, programadoPara });
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
}