import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/** Una carpeta o atajo que devuelve el explorador del sistema. */
export interface FsItem {
  nombre: string;
  ruta: string;
}

/** Resultado de abrir una carpeta: su contenido y los atajos disponibles. */
export interface FsListado {
  ruta: string;
  padre: string | null;
  separador: string;
  carpetas: FsItem[];
  sitios: FsItem[];
  unidades: FsItem[];
}

/**
 * Explorador de carpetas del sistema (a través del backend) para elegir rutas
 * con el botón "…" en los campos de carpeta.
 */
@Injectable({ providedIn: 'root' })
export class FsService {
  constructor(private api: ApiService) {}

  listar(ruta: string): Observable<FsListado> {
    return this.api.get<FsListado>(`/api/fs/listar?ruta=${encodeURIComponent(ruta || '')}`);
  }
}
