import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface PlantillaCaption {
  plantilla: string;
  placeholders: string[];
}

@Injectable({ providedIn: 'root' })
export class SettingsService {
  constructor(private api: ApiService) {}

  obtenerPlantillaCaption(): Observable<PlantillaCaption> {
    return this.api.get<PlantillaCaption>('/api/settings/caption-template');
  }

  guardarPlantillaCaption(plantilla: string): Observable<{ plantilla: string }> {
    return this.api.put<{ plantilla: string }>('/api/settings/caption-template', { plantilla });
  }
}
