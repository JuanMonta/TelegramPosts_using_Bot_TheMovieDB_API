import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { LinkVideo, Titulo, TituloDetalle } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class TitulosService {
  constructor(private api: ApiService) {}

  listar(): Observable<Titulo[]> {
    return this.api.get<Titulo[]>('/api/titulos');
  }

  detalle(id: number): Observable<TituloDetalle> {
    return this.api.get<TituloDetalle>(`/api/titulos/${id}`);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/api/titulos/${id}`);
  }

  agregarLink(id: number, texto: string, url: string, orden: number, teraboxFilename?: string): Observable<LinkVideo> {
    return this.api.post<LinkVideo>(`/api/titulos/${id}/links`, { texto, url, terabox_filename: teraboxFilename ?? null, orden });
  }

  editarLink(id: number, linkId: number, texto: string, url: string, orden: number, teraboxFilename?: string): Observable<LinkVideo> {
    return this.api.put<LinkVideo>(`/api/titulos/${id}/links/${linkId}`, { texto, url, terabox_filename: teraboxFilename ?? null, orden });
  }

  eliminarLink(id: number, linkId: number): Observable<void> {
    return this.api.delete<void>(`/api/titulos/${id}/links/${linkId}`);
  }
}