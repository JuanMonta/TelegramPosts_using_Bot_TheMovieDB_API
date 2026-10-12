import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { Acoplamiento } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class AcoplamientoService {
  constructor(private api: ApiService) {}

  listar(): Observable<Acoplamiento[]> {
    return this.api.get<Acoplamiento[]>('/api/acoplamientos');
  }

  crear(nombre: string): Observable<Acoplamiento> {
    return this.api.post<Acoplamiento>('/api/acoplamientos', { nombre });
  }

  actualizar(id: number, nombre: string): Observable<Acoplamiento> {
    return this.api.put<Acoplamiento>(`/api/acoplamientos/${id}`, { nombre });
  }

  agregarCanal(id: number, canalId: number): Observable<Acoplamiento> {
    return this.api.post<Acoplamiento>(`/api/acoplamientos/${id}/canales/${canalId}`, {});
  }

  quitarCanal(id: number, canalId: number): Observable<Acoplamiento> {
    return this.api.delete<Acoplamiento>(`/api/acoplamientos/${id}/canales/${canalId}`);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/api/acoplamientos/${id}`);
  }
}
