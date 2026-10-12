import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { Bot } from '../models/modelos';

@Injectable({ providedIn: 'root' })
export class BotService {
  constructor(private api: ApiService) {}

  listar(): Observable<Bot[]> {
    return this.api.get<Bot[]>('/api/bots');
  }

  crear(nombre: string, token: string): Observable<Bot> {
    return this.api.post<Bot>('/api/bots', { nombre, token });
  }

  actualizar(id: number, datos: { nombre?: string; token?: string }): Observable<Bot> {
    return this.api.put<Bot>(`/api/bots/${id}`, datos);
  }

  eliminar(id: number): Observable<void> {
    return this.api.delete<void>(`/api/bots/${id}`);
  }
}
