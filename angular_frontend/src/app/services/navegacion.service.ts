import { Injectable } from '@angular/core';

/**
 * Rastrea la navegación entre la página principal (raíz '/') y sus páginas
 * hijas (el gestor /publicacion/:id). Sirve para que "volver" haga pop en el
 * historial (location.back) en lugar de apilar una entrada nueva de '/'.
 * Así la raíz existe una sola vez en el historial y no hay nada antes que ella.
 */
@Injectable({ providedIn: 'root' })
export class NavegacionService {
  /** true mientras el gestor fue abierto desde la página principal. */
  vinoDeHome = false;
  /** true para abrir Configuración al llegar a Home (viene de la tira del editor). */
  abrirCfgAlLlegar = false;
}
