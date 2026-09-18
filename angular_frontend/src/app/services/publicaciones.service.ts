import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { CatalogoRespuesta, Comentario, Publicacion } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class PublicacionesService {
  constructor(private api: ApiService) {}

  listar(): Observable<Publicacion[]> {
    return this.api.get<Publicacion[]>('/api/publicaciones');
  }

  /** Catálogo ligero para el gestor: filas de título + estado + conteos. */
  catalogo(): Observable<CatalogoRespuesta> {
    return this.api.get<CatalogoRespuesta>('/api/publicaciones/catalogo');
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

  comentarios(publicacionId: number): Observable<Comentario[]> {
    return this.api.get<Comentario[]>(`/api/publicaciones/${publicacionId}/comentarios`);
  }
}