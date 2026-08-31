import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface CaptionTemplate {
  id: number;
  name: string;
  template: string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class CaptionTemplateService {
  constructor(private api: ApiService) {}

  listar(): Observable<CaptionTemplate[]> {
    return this.api.get<CaptionTemplate[]>('/api/caption-templates');
  }

  obtenerDefault(): Observable<CaptionTemplate> {
    return this.api.get<CaptionTemplate>('/api/caption-templates/default');
  }

  obtener(id: number): Observable<CaptionTemplate> {
    return this.api.get<CaptionTemplate>(`/api/caption-templates/${id}`);
  }

  crear(name: string, template: string, isDefault = false): Observable<CaptionTemplate> {
    return this.api.post<CaptionTemplate>('/api/caption-templates', { name, template, isDefault });
  }

  actualizar(id: number, data: Partial<{ name: string; template: string; isDefault: boolean }>): Observable<CaptionTemplate> {
    return this.api.put<CaptionTemplate>(`/api/caption-templates/${id}`, data);
  }

  borrar(id: number): Observable<void> {
    return this.api.delete<void>(`/api/caption-templates/${id}`);
  }
}