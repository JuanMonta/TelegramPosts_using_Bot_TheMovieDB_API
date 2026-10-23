import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { tap } from 'rxjs/operators';
import { ApiService } from './api.service';
import { Acoplamiento } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class AcoplamientoService {
  private cambios = new Subject<void>();
  /** Avisa a las vistas abiertas de que la lista de conjuntos cambió. */
  readonly cambios$: Observable<void> = this.cambios.asObservable();

  constructor(private api: ApiService) {}

  listar(): Observable<Acoplamiento[]> {
    return this.api.get<Acoplamiento[]>('/api/acoplamientos');
  }

  crear(nombre: string): Observable<Acoplamiento> {
    return this.api.post<Acoplamiento>('/api/acoplamientos', { nombre })
      .pipe(tap(() => this.cambios.next()));
  }

  actualizar(id: number, nombre: string): Observable<Acoplamiento> {
    return this.api.put<Acoplamiento>(`/api/acoplamientos/${id}`, { nombre })
      .pipe(tap(() => this.cambios.next()));
  }

  agregarCanal(id: number, canalId: number): Observable<Acoplamiento> {
    return this.api.post<Acoplamiento>(`/api/acoplamientos/${id}/canales/${canalId}`, {})
      .pipe(tap(() => this.cambios.next()));
  }

  quitarCanal(id: number, canalId: number): Observable<Acoplamiento> {
    return this.api.delete<Acoplamiento>(`/api/acoplamientos/${id}/canales/${canalId}`)
      .pipe(tap(() => this.cambios.next()));
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/api/acoplamientos/${id}`)
      .pipe(tap(() => this.cambios.next()));
  }
}
