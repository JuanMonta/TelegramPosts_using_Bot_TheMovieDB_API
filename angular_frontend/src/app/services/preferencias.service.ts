import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface BotDetectarPreferencia {
  bot_id: number | null;
  nombre: string | null;
}

@Injectable({ providedIn: 'root' })
export class PreferenciasService {
  constructor(private api: ApiService) {}

  obtenerBotDetectar(): Observable<BotDetectarPreferencia> {
    return this.api.get<BotDetectarPreferencia>('/api/settings/bot-detectar');
  }

  guardarBotDetectar(botId: number | null): Observable<BotDetectarPreferencia> {
    return this.api.put<BotDetectarPreferencia>('/api/settings/bot-detectar', { bot_id: botId });
  }
}
