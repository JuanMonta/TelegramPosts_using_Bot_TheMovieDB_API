import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { Publicacion } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class PublicacionesService {
  constructor(private api: ApiService) {}

  listar(): Observable<Publicacion[]> {
    return this.api.get<Publicacion[]>('/api/publicaciones');
  }

  crear(tituloId: number, caption: string): Observable<Publicacion> {
    return this.api.post<Publicacion>('/api/publicaciones', { tituloId, caption });
  }

  editar(id: number, caption: string): Observable<Publicacion> {
    return this.api.put<Publicacion>(`/api/publicaciones/${id}`, { caption });
  }

  publicar(id: number): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/publicar`);
  }

  editarCanal(id: number): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/editar`);
  }

  republicar(id: number): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/republicar`);
  }
}