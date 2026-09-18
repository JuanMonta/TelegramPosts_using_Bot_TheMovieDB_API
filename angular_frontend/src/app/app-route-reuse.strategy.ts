import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, DetachedRouteHandle, RouteReuseStrategy } from '@angular/router';

/**
 * Conserva la instancia de HomeComponent entre navegaciones para que su estado
 * (post seleccionado, filtros, búsqueda, vista lista/grid, paginación, panel de
 * comentarios) sobreviva al ir y volver de /publicacion/:id.
 * Solo se cachea la ruta raíz (''); el editor no se reutiliza.
 */
@Injectable()
export class AppRouteReuseStrategy implements RouteReuseStrategy {
  private readonly cache = new Map<string, DetachedRouteHandle>();

  shouldDetach(route: ActivatedRouteSnapshot): boolean {
    return route.routeConfig?.path === '';
  }

  store(route: ActivatedRouteSnapshot, handle: DetachedRouteHandle): void {
    this.cache.set(this.clave(route), handle);
  }

  shouldAttach(route: ActivatedRouteSnapshot): boolean {
    return this.cache.has(this.clave(route));
  }

  retrieve(route: ActivatedRouteSnapshot): DetachedRouteHandle | null {
    return this.cache.get(this.clave(route)) ?? null;
  }

  shouldReuseRoute(route: ActivatedRouteSnapshot, future: ActivatedRouteSnapshot): boolean {
    return route.routeConfig === future.routeConfig;
  }

  private clave(route: ActivatedRouteSnapshot): string {
    return route.routeConfig?.path ?? '__root__';
  }
}