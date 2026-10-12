import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { Acoplamiento } from '../models/modelos';

/**
 * Conjunto de trabajo: el acoplamiento activo en Home y editor, como el modo.
 * Persiste entre sesiones, pero arranca vacío: jamás auto-elige uno.
 */
@Injectable({ providedIn: 'root' })
export class ConjuntoTrabajoService {
  private static readonly CLAVE = 'crow_conjunto_trabajo';
  private id = new BehaviorSubject<number | null>(null);
  readonly id$: Observable<number | null> = this.id.asObservable();

  get actual(): number | null {
    return this.id.value;
  }

  elegir(id: number | null): void {
    this.id.next(id);
    try {
      if (id === null) localStorage.removeItem(ConjuntoTrabajoService.CLAVE);
      else localStorage.setItem(ConjuntoTrabajoService.CLAVE, String(id));
    } catch {
      // sin almacenamiento: la selección vive solo en memoria
    }
  }

  /** Restaura el guardado solo si sigue existiendo; si no, queda vacío. */
  restaurar(lista: Acoplamiento[]): void {
    try {
      const raw = localStorage.getItem(ConjuntoTrabajoService.CLAVE);
      const id = raw === null ? NaN : Number(raw);
      this.id.next(!Number.isNaN(id) && lista.some((a) => a.id === id) ? id : null);
    } catch {
      this.id.next(null);
    }
  }

  entidad(lista: Acoplamiento[]): Acoplamiento | undefined {
    const id = this.id.value;
    return id === null ? undefined : lista.find((a) => a.id === id);
  }

  /** Limpia la selección si el conjunto ya no existe (ej. se borró en Configuración). */
  limpiarSiFalta(lista: Acoplamiento[]): void {
    const id = this.id.value;
    if (id !== null && !lista.some((a) => a.id === id)) this.elegir(null);
  }
}
