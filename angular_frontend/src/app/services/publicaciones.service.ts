import { Injectable } from '@angular/core';
import { forkJoin, Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { ApiService } from './api.service';
import { CatalogoRespuesta, Comentario, LinkVideo, Publicacion, PublicacionMedia } from '../models/modelos';

/** Catálogo ligero más el índice título → publicación completa. */
export interface VistaCatalogo {
  catalogo: CatalogoRespuesta;
  porTitulo: Record<number, Publicacion>;
}

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

  /** Catálogo + publicaciones en paralelo, con el índice título→publicación ya construido.
   *  Si falla `listar()`, el catálogo sigue siendo usable (se cae a un índice vacío). */
  vistaCatalogo(): Observable<VistaCatalogo> {
    return forkJoin([
      this.catalogo(),
      this.listar().pipe(catchError(() => of([] as Publicacion[])))
    ]).pipe(
      map(([catalogo, pubs]) => ({
        catalogo,
        porTitulo: pubs.reduce<Record<number, Publicacion>>((mapa, p) => {
          if (p.titulo_id != null) {
            mapa[p.titulo_id] = p;
          }
          return mapa;
        }, {})
      }))
    );
  }

  crear(tituloId: number, caption: string): Observable<Publicacion> {
    return this.api.post<Publicacion>('/api/publicaciones', { tituloId, caption });
  }

  /** Crea un post personalizado (sin TMDB). */
  crearPersonalizado(caption: string): Observable<Publicacion> {
    return this.api.post<Publicacion>('/api/publicaciones', { tipo: 'personalizado', caption });
  }

  detalle(id: number): Observable<Publicacion> {
    return this.api.get<Publicacion>(`/api/publicaciones/${id}`);
  }

  // ---- imágenes del post ----

  media(id: number): Observable<PublicacionMedia[]> {
    return this.api.get<PublicacionMedia[]>(`/api/publicaciones/${id}/media`);
  }

  subirImagen(id: number, file: File): Observable<PublicacionMedia> {
    const form = new FormData();
    form.append('file', file);
    return this.api.postForm<PublicacionMedia>(`/api/publicaciones/${id}/media/subir`, form);
  }

  adjuntarTmdb(id: number, tipo: string, path: string): Observable<PublicacionMedia> {
    return this.api.post<PublicacionMedia>(`/api/publicaciones/${id}/media/tmdb`, { tipo, path });
  }

  borrarMedia(id: number, mediaId: number): Observable<void> {
    return this.api.delete<void>(`/api/publicaciones/${id}/media/${mediaId}`);
  }

  ordenarMedia(id: number, ids: number[]): Observable<PublicacionMedia[]> {
    return this.api.put<PublicacionMedia[]>(`/api/publicaciones/${id}/media/orden`, { ids });
  }

  // ---- enlaces del post personalizado ----

  links(id: number): Observable<LinkVideo[]> {
    return this.api.get<LinkVideo[]>(`/api/publicaciones/${id}/links`);
  }

  agregarLink(id: number, texto: string, url: string, orden: number, terabox?: string): Observable<LinkVideo> {
    return this.api.post<LinkVideo>(`/api/publicaciones/${id}/links`, { texto, url, orden, terabox_filename: terabox ?? '' });
  }

  editarLink(id: number, linkId: number, texto: string, url: string, orden: number, terabox?: string): Observable<LinkVideo> {
    return this.api.put<LinkVideo>(`/api/publicaciones/${id}/links/${linkId}`, { texto, url, orden, terabox_filename: terabox ?? '' });
  }

  eliminarLink(id: number, linkId: number): Observable<void> {
    return this.api.delete<void>(`/api/publicaciones/${id}/links/${linkId}`);
  }

  editar(id: number, caption: string): Observable<Publicacion> {
    return this.api.put<Publicacion>(`/api/publicaciones/${id}`, { caption });
  }

  publicar(id: number, acoplamientoId?: number | null): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/publicar`, { acoplamientoId });
  }

  editarCanal(id: number): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/editar`);
  }

  republicar(id: number): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/republicar`);
  }

  /** Elimina el mensaje del canal y devuelve el registro a borrador. */
  eliminarDelCanal(id: number): Observable<Publicacion> {
    return this.api.post<Publicacion>(`/api/publicaciones/${id}/eliminar-del-canal`, {});
  }

  /** Elimina por completo un post personalizado (con sus imágenes y enlaces). */
  eliminarPost(id: number): Observable<void> {
    return this.api.delete<void>(`/api/publicaciones/${id}`);
  }

  comentarios(publicacionId: number): Observable<Comentario[]> {
    return this.api.get<Comentario[]>(`/api/publicaciones/${publicacionId}/comentarios`);
  }
}