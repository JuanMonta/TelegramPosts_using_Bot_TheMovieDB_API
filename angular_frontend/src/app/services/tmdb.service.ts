import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { SugerenciaHit, TmdbHit, TmdbImagen, Titulo } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class TmdbService {
  constructor(private api: ApiService) {}

  buscar(q: string): Observable<TmdbHit[]> {
    return this.api.get<TmdbHit[]>(`/api/tmdb/search?q=${encodeURIComponent(q)}`);
  }

  sugerir(q: string): Observable<SugerenciaHit> {
    return this.api.get<SugerenciaHit>(`/api/tmdb/sugerir?q=${encodeURIComponent(q)}`);
  }

  guardar(tipo: string, tmdbId: number): Observable<Titulo> {
    return this.api.get<Titulo>(`/api/tmdb/guardar?tipo=${tipo}&tmdbId=${tmdbId}`);
  }

  /** Pósters y backdrops de una película/serie para elegir en el editor. */
  imagenes(tipo: string, tmdbId: number): Observable<TmdbImagen[]> {
    return this.api.get<TmdbImagen[]>(`/api/tmdb/${tipo}/${tmdbId}/imagenes`);
  }
}