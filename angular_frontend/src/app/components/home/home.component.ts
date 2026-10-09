import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { PublicacionesService } from '../../services/publicaciones.service';
import { TmdbService } from '../../services/tmdb.service';
import { ApiService } from '../../services/api.service';
import { NavegacionService } from '../../services/navegacion.service';
import { ColaService } from '../../services/cola.service';
import {
  CatalogoFila,
  ColaItem,
  Comentario,
  Publicacion,
  SugerenciaHit,
  TmdbHit,
  etiquetaTipo,
  urlPoster
} from '../../models/modelos';

const PASO_VISIBLES = 30;

type Vista = 'lista' | 'grid';

type FiltroEstado = 'todos' | 'borrador' | 'publicado';

interface CalCelda {
  fecha: Date;
  clave: string;
  num: number;
  fuera: boolean;
  hoy: boolean;
  seleccionado: boolean;
  total: number;
  publicados: number;
  borradores: number;
}

@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css']
})
export class HomeComponent implements OnInit, OnDestroy {
  cargando = true;
  errorBackend: string | null = null;

  // catálogo de títulos guardados
  catalogo: CatalogoFila[] = [];
  total = 0;
  borradores = 0;
  publicados = 0;
  sin = 0;

  filtroEstado: FiltroEstado = 'todos';
  busquedaCatalogo = '';
  vista: Vista = 'lista';
  filasVisibles = PASO_VISIBLES;

  // calendario desplegable (funciona sobre lista y grid)
  calendarioActivo = false;
  readonly diasSemana = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];
  private readonly MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];
  mesAnio = new Date().getFullYear();
  mesMes = new Date().getMonth();
  diaSeleccionado: string | null = null;

  // panel "escena"
  panel: CatalogoFila | null = null;
  porTitulo: Record<number, Publicacion | undefined> = {};

  // scroll de la lista (sobrevive al ir/volver del editor)
  @ViewChild('paneCuerpo') paneCuerpo!: ElementRef<HTMLElement>;
  scrollLista = 0;

  // búsqueda TMDB para agregar
  busqueda = '';
  buscando = false;
  buscado = false;
  resultados: TmdbHit[] = [];
  errorBusqueda: string | null = null;
  sugerencia: SugerenciaHit | null = null;
  guardandoTmdb: Record<number, boolean> = {};

  // panel de comentarios (tercera columna)
  panelComentariosAbierto = false;
  /** Memoria de la preferencia del usuario: si abrió el panel aunque ahora esté
   *  visualmente cerrado por estar viendo un borrador, al volver a un post
   *  publicado se reabre automáticamente. Se limpia al cerrar explícitamente. */
  recordarPanelComentarios = false;
  comentariosPost: Comentario[] = [];
  cargandoComentarios = false;

  private killed = new Subject<void>();
  /** Distingue la carga inicial de las vueltas posteriores a la ruta. */
  private primeraCarga = true;

  // cola de publicaciones programadas (modal)
  colaAbierta = false;
  colaItems: ColaItem[] = [];
  cargandoCola = false;
  colaNuevoId = 0;
  colaNuevaFecha = '';
  colaAccion = false;
  private colaTimer: number | undefined;

  constructor(
    private publicacionesService: PublicacionesService,
    private tmdbService: TmdbService,
    private api: ApiService,
    private snack: MatSnackBar,
    private router: Router,
    private navegacion: NavegacionService,
    private colaService: ColaService
  ) {}

  ngOnInit(): void {
    this.cargar();
    this.refrescarColaSilencioso();
    // La instancia se reutiliza (RouteReuseStrategy). Al volver de /publicacion/:id
    // se refrescan los datos en silencio conservando el estado de la UI
    // (selección, filtros, búsqueda, vista, panel de comentarios).
    this.router.events.pipe(takeUntil(this.killed)).subscribe((event) => {
      if (event instanceof NavigationEnd && event.url === '/') {
        if (this.primeraCarga) {
          this.primeraCarga = false;
        } else {
          this.refrescarDatos();
          // el reattache resetea scrollTop a 0: lo restauramos tras montar la vista.
          setTimeout(() => this.restaurarScrollLista(), 0);
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.killed.next();
    this.killed.complete();
    if (this.colaTimer !== undefined) {
      window.clearInterval(this.colaTimer);
    }
  }

  cargar(): void {
    this.cargando = true;
    this.errorBackend = null;
    this.publicacionesService.catalogo().pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        this.total = c.total;
        this.borradores = c.borradores;
        this.publicados = c.publicados;
        this.sin = c.sin;
        this.catalogo = c.items;
        this.reseteoPaginado();
        this.publicacionesService.listar().pipe(takeUntil(this.killed)).subscribe({
          next: (pubs) => {
            const mapa: Record<number, Publicacion | undefined> = {};
            pubs.forEach((p) => { mapa[p.titulo_id] = p; });
            this.porTitulo = mapa;
            this.panel = this.elegirPanel();
            this.cargando = false;
          },
          error: () => {
            this.panel = this.elegirPanel();
            this.cargando = false;
          }
        });
      },
      error: (e: HttpErrorResponse) => this.fallarCarga(e)
    });
  }

  private fallarCarga(e: HttpErrorResponse): void {
    this.errorBackend = this.api.mensajeDeError(e);
    this.cargando = false;
  }

  /** Refresca el catálogo conservando el estado de la UI (selección, filtros,
   *  paginación, vista). Se dispara al volver a la ruta desde el editor. */
  recargarTrasModo(): void {
    this.filtroEstado = 'todos';
    this.busquedaCatalogo = '';
    this.panelComentariosAbierto = false;
    this.refrescarDatos();
  }

  private refrescarDatos(): void {
    this.publicacionesService.catalogo().pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        this.total = c.total;
        this.borradores = c.borradores;
        this.publicados = c.publicados;
        this.sin = c.sin;
        this.catalogo = c.items;
        this.publicacionesService.listar().pipe(takeUntil(this.killed)).subscribe({
          next: (pubs) => {
            const mapa: Record<number, Publicacion | undefined> = {};
            pubs.forEach((p) => { mapa[p.titulo_id] = p; });
            this.porTitulo = mapa;
            // conserva el post seleccionado si sigue existiendo; si no, cae de nuevo al panel.
            if (this.panel && !this.catalogo.some((t) => t.id === this.panel!.id)) {
              this.panel = this.elegirPanel();
            }
            if (this.panelComentariosAbierto && this.panel) {
              this.cargarComentariosDe(this.panel);
            }
            this.refrescarColaSilencioso();
            setTimeout(() => this.restaurarScrollLista(), 0);
          },
          error: () => { /* el estado anterior sigue en pie si el refresh falla */ }
        });
      },
      error: () => { /* el estado anterior sigue en pie si el refresh falla */ }
    });
  }

  /** Captura el scroll de la lista (se dispara en cada evento (scroll)). */
  guardarScrollLista(): void {
    if (this.paneCuerpo) {
      this.scrollLista = this.paneCuerpo.nativeElement.scrollTop;
    }
  }

  /** Restaura el scroll de la lista tras volver de otra ruta. */
  restaurarScrollLista(): void {
    if (this.paneCuerpo) {
      this.paneCuerpo.nativeElement.scrollTop = this.scrollLista;
    }
  }

  // ---- catálogo: filtros / vistas / paginación ----

  filtradas(): CatalogoFila[] {
    let lista = this.catalogo;
    if (this.filtroEstado !== 'todos') {
      lista = lista.filter((t) => t.estado === this.filtroEstado);
    }
    const q = normalizar(this.busquedaCatalogo.trim());
    if (q) {
      lista = lista.filter((t) => {
        if (normalizar(t.titulo).includes(q)) return true;
        if (t.anio !== null && t.anio !== undefined && String(t.anio).includes(q)) return true;
        const etiqueta = t.tipo === 'movie' ? 'pelicula' : 'serie';
        if (etiqueta.includes(q)) return true;
        return false;
      });
    }
    return lista;
  }

  /** Posts filtrados por pestaña/búsqueda y, si el calendario está activo, por día. */
  private filtradasConCalendario(): CatalogoFila[] {
    let lista = this.filtradas();
    if (this.calendarioActivo && this.diaSeleccionado) {
      lista = lista.filter((t) => {
        if (t.estado === 'sin') return false;
        const f = this.fechaDeItem(t);
        return f !== null && this.fechaClave(f) === this.diaSeleccionado;
      });
    }
    return lista;
  }

  get visibles(): CatalogoFila[] {
    if (this.calendarioActivo && this.diaSeleccionado) {
      return this.filtradasConCalendario().slice(0, this.filasVisibles);
    }
    return this.filtradas().slice(0, this.filasVisibles);
  }

  get hayMas(): boolean {
    const total = this.calendarioActivo && this.diaSeleccionado
      ? this.filtradasConCalendario().length
      : this.filtradas().length;
    return total > this.filasVisibles;
  }

  get restantes(): number {
    const total = this.calendarioActivo && this.diaSeleccionado
      ? this.filtradasConCalendario().length
      : this.filtradas().length;
    return total - this.filasVisibles;
  }

  verMas(): void {
    this.filasVisibles += PASO_VISIBLES;
  }

  cambiarEstado(e: FiltroEstado): void {
    this.filtroEstado = e;
    this.reseteoPaginado();
  }

  onChangeBusquedaCatalogo(): void {
    this.reseteoPaginado();
  }

  limpiarBusquedaCatalogo(): void {
    this.busquedaCatalogo = '';
    this.reseteoPaginado();
  }

  cambiarVista(v: Vista): void {
    this.vista = v;
  }

  toggleCalendario(): void {
    this.calendarioActivo = !this.calendarioActivo;
    if (!this.calendarioActivo) {
      this.diaSeleccionado = null;
    }
    this.reseteoPaginado();
  }

  // ---- calendario ----

  get etiquetaMes(): string {
    return `${this.MESES[this.mesMes]} ${this.mesAnio}`;
  }

  get etiquetaDiaSeleccionado(): string {
    if (!this.diaSeleccionado) return '';
    const [y, m, d] = this.diaSeleccionado.split('-').map(Number);
    const fecha = new Date(y, m - 1, d);
    return fecha.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  }

  mesAnterior(): void {
    this.mesMes--;
    if (this.mesMes < 0) {
      this.mesMes = 11;
      this.mesAnio--;
    }
    this.preseleccionarMes();
  }

  mesSiguiente(): void {
    this.mesMes++;
    if (this.mesMes > 11) {
      this.mesMes = 0;
      this.mesAnio++;
    }
    this.preseleccionarMes();
  }

  irHoy(): void {
    const hoy = new Date();
    this.mesAnio = hoy.getFullYear();
    this.mesMes = hoy.getMonth();
    this.diaSeleccionado = this.fechaClave(hoy);
  }

  /** Tras navegar de mes, deja seleccionado el primer día del mes que tenga posts. */
  private preseleccionarMes(): void {
    const conPosts = this.celdasMes().filter((c) => !c.fuera && c.total > 0);
    this.diaSeleccionado = conPosts.length ? conPosts[0].clave : null;
  }

  /** 42 celdas (6 semanas desde el domingo) del mes activo, con sus conteos. */
  celdasMes(): CalCelda[] {
    const mesActivo = this.mesMes;
    const anioActivo = this.mesAnio;
    const primer = new Date(anioActivo, mesActivo, 1);
    const inicio = new Date(anioActivo, mesActivo, 1 - primer.getDay());
    const hoyClave = this.fechaClave(new Date());

    const porClave = new Map<string, { publicados: number; borradores: number; total: number }>();
    for (const t of this.itemsConPost()) {
      const f = this.fechaDeItem(t);
      if (!f) continue;
      const k = this.fechaClave(f);
      let e = porClave.get(k);
      if (!e) {
        e = { publicados: 0, borradores: 0, total: 0 };
        porClave.set(k, e);
      }
      e.total++;
      if (t.estado === 'publicado') e.publicados++;
      else e.borradores++;
    }

    const celdas: CalCelda[] = [];
    for (let i = 0; i < 42; i++) {
      const fecha = new Date(anioActivo, mesActivo, 1 - primer.getDay() + i);
      const clave = this.fechaClave(fecha);
      const cnt = porClave.get(clave);
      celdas.push({
        fecha,
        clave,
        num: fecha.getDate(),
        fuera: fecha.getMonth() !== mesActivo || fecha.getFullYear() !== anioActivo,
        hoy: clave === hoyClave,
        seleccionado: clave === this.diaSeleccionado,
        total: cnt?.total ?? 0,
        publicados: cnt?.publicados ?? 0,
        borradores: cnt?.borradores ?? 0
      });
    }
    return celdas;
  }

  elegirDia(c: CalCelda): void {
    if (this.diaSeleccionado === c.clave) {
      this.diaSeleccionado = null;
    } else {
      this.diaSeleccionado = c.clave;
    }
    this.reseteoPaginado();
  }

  limpiarDiaCalendario(): void {
    this.diaSeleccionado = null;
    this.reseteoPaginado();
  }

  /** Posts del día seleccionado (respeta filtro de estado y búsqueda activos). */
  postsDelDia(): CatalogoFila[] {
    if (!this.diaSeleccionado) return [];
    return this.itemsConPost().filter((t) => {
      const f = this.fechaDeItem(t);
      return f !== null && this.fechaClave(f) === this.diaSeleccionado;
    });
  }

  /** Item con publicación propia (excluye 'sin'); los 'sin' no tienen fecha de post. */
  private itemsConPost(): CatalogoFila[] {
    return this.filtradas().filter((t) => t.estado !== 'sin');
  }

  private fechaDeItem(t: CatalogoFila): Date | null {
    if (!t.fecha) return null;
    const d = new Date(t.fecha);
    return isNaN(d.getTime()) ? null : d;
  }

  private fechaClave(d: Date): string {
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${m}-${dd}`;
  }

  private reseteoPaginado(): void {
    this.filasVisibles = PASO_VISIBLES;
  }

  private elegirPanel(): CatalogoFila | null {
    if (!this.catalogo.length) return null;
    return this.catalogo.find((t) => t.estado === 'borrador')
      ?? this.catalogo[this.catalogo.length - 1];
  }

  // ---- selección / previsualización ----

  /** Selecciona un post para previsualizar en el panel central.
   *  Los borradores/sin-post no tienen comentarios: el panel se cierra de forma
   *  visual pero se conserva la preferencia. Al volver a un post publicado,
   *  si el usuario quería el panel abierto, se reabre y carga sus comentarios. */
  seleccionar(t: CatalogoFila): void {
    this.panel = t;
    if (t.estado !== 'publicado') {
      this.panelComentariosAbierto = false;
      this.comentariosPost = [];
      return;
    }
    if (this.recordarPanelComentarios) {
      this.panelComentariosAbierto = true;
      this.cargarComentariosDe(t);
    }
  }

  /** Navega al editor del post. */
  abrir(t: { id: number }): void {
    this.navegacion.vinoDeHome = true;
    this.router.navigate(['/publicacion', t.id]);
  }

  // ---- cola de publicaciones programadas ----

  abrirCola(): void {
    this.colaAbierta = true;
    this.cargarCola();
    if (this.colaTimer === undefined) {
      this.colaTimer = window.setInterval(() => this.cargarColaSilencioso(), 20000);
    }
  }

  cerrarCola(): void {
    this.colaAbierta = false;
    if (this.colaTimer !== undefined) {
      window.clearInterval(this.colaTimer);
      this.colaTimer = undefined;
    }
    this.refrescarDatos();
  }

  private cargarCola(): void {
    this.cargandoCola = true;
    this.colaService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (items) => {
        this.colaItems = items.sort((a, b) => (a.programado_para ?? '').localeCompare(b.programado_para ?? ''));
        this.cargandoCola = false;
      },
      error: () => {
        this.cargandoCola = false;
      }
    });
  }

  private cargarColaSilencioso(): void {
    if (!this.colaAbierta || this.colaAccion) return;
    this.colaService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (items) => (this.colaItems = items.sort((a, b) => (a.programado_para ?? '').localeCompare(b.programado_para ?? '')))
    });
  }

  /** Actualiza la lista de la cola (para el badge del header) sin abrir el modal. */
  private refrescarColaSilencioso(): void {
    this.colaService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (items) => (this.colaItems = items.sort((a, b) => (a.programado_para ?? '').localeCompare(b.programado_para ?? ''))),
      error: () => undefined
    });
  }

  /** Items pendientes de publicar (badge del botón COLA). */
  get colaPendientes(): number {
    return this.colaItems.filter((i) => i.estado === 'pendiente' || i.estado === 'publicando').length;
  }

  /** Borradores disponibles para añadir a la cola (solo los que no tienen item activo). */
  colaBorradores(): CatalogoFila[] {
    const activos = new Set(this.colaItems.filter((i) => i.estado === 'pendiente' || i.estado === 'publicando').map((i) => i.publicacion_id));
    return this.catalogo.filter((t) => t.estado === 'borrador' && t.publicacion_id != null && !activos.has(t.publicacion_id));
  }

  programarNuevo(): void {
    if (!this.colaNuevoId || !this.colaNuevaFecha || this.colaAccion) return;
    this.colaAccion = true;
    this.colaService.programar(this.colaNuevoId, this.colaNuevaFecha).pipe(takeUntil(this.killed)).subscribe({
      next: (item) => {
        this.colaItems = [...this.colaItems, item];
        this.colaNuevoId = 0;
        this.colaNuevaFecha = '';
        this.colaAccion = false;
        this.snack.open('Añadido a la cola', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.colaAccion = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }

  cancelarItemCola(item: ColaItem): void {
    if (this.colaAccion || item.estado !== 'pendiente') return;
    this.colaAccion = true;
    this.colaService.cancelar(item.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.colaItems = this.colaItems.map((i) => (i.id === item.id ? { ...i, estado: 'cancelado' } : i));
        this.colaAccion = false;
      },
      error: (e: HttpErrorResponse) => {
        this.colaAccion = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        // La cola pudo publicar el post justo en ese momento: refrescar la lista.
        this.cargarColaSilencioso();
      }
    });
  }

  /** Vuelve a poner un item fallido/cancelado en 'pendiente' (se publica en el próximo tick, ~30s). */
  reintentarItemCola(item: ColaItem): void {
    if (this.colaAccion || (item.estado !== 'fallo' && item.estado !== 'cancelado')) return;
    this.colaAccion = true;
    this.colaService.reintentar(item.id).pipe(takeUntil(this.killed)).subscribe({
      next: (actualizado) => {
        this.colaItems = this.colaItems.map((i) => (i.id === item.id ? actualizado : i));
        this.colaAccion = false;
        this.snack.open('Reintentando en los próximos segundos…', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.colaAccion = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        this.cargarColaSilencioso();
      }
    });
  }

  etiquetaEstadoCola(e: string): string {
    const mapa: Record<string, string> = {
      pendiente: 'PENDIENTE',
      publicando: 'PUBLICANDO…',
      publicado: 'PUBLICADO',
      fallo: 'FALLÓ',
      cancelado: 'CANCELADO'
    };
    return mapa[e] ?? e;
  }

  /** "29 ago 2026 · 21:00" para las fechas de la cola. */
  fmtColaFecha(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    const fecha = d.toLocaleDateString('es-MX', { day: '2-digit', month: 'short', year: 'numeric' });
    const hora = d.toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit' });
    return `${fecha} · ${hora}`;
  }

  // ---- helpers de presentación ----

  poster(t: { poster_path: string | null }): string | null {
    return urlPoster(t.poster_path);
  }

  posterUrl(p: string | null): string | null {
    return urlPoster(p);
  }

  tipo(t: 'movie' | 'tv'): string {
    return etiquetaTipo(t);
  }

  nombreTitulo(s: string): string {
    const c = s.trim().charAt(0);
    return c ? c.toUpperCase() : '?';
  }

  captionPanel(t: { id: number }): string | null {
    const p = this.porTitulo[t.id];
    return p ? p.caption : null;
  }

  // ---- panel de comentarios ----

  abrirPanelComentarios(): void {
    if (!this.panel) return;
    this.recordarPanelComentarios = true;
    this.panelComentariosAbierto = true;
    this.cargarComentariosDe(this.panel);
  }

  cerrarPanelComentarios(): void {
    this.recordarPanelComentarios = false;
    this.panelComentariosAbierto = false;
    this.comentariosPost = [];
  }

  /** Carga los comentarios de un post en el panel lateral. */
  private cargarComentariosDe(t: CatalogoFila): void {
    if (!t.publicacion_id) {
      this.comentariosPost = [];
      this.cargandoComentarios = false;
      return;
    }
    this.cargandoComentarios = true;
    this.publicacionesService.comentarios(t.publicacion_id).pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        if (this.panel?.id === t.id) {
          this.comentariosPost = c;
          this.cargandoComentarios = false;
        }
      },
      error: () => {
        if (this.panel?.id === t.id) {
          this.comentariosPost = [];
          this.cargandoComentarios = false;
        }
      }
    });
  }

  // ---- búsqueda TMDB (agregar) ----

  buscar(termino?: string): void {
    const q = (termino ?? this.busqueda).trim();
    if (!q) return;
    this.busqueda = q;
    this.buscando = true;
    this.buscado = true;
    this.errorBusqueda = null;
    this.sugerencia = null;
    this.tmdbService.buscar(q).pipe(takeUntil(this.killed)).subscribe({
      next: (r) => {
        this.resultados = r;
        this.buscando = false;
        if (!r.length) {
          this.tmdbService.sugerir(q).pipe(takeUntil(this.killed)).subscribe({
            next: (s) => {
              this.sugerencia = s && s.encontrado ? s : null;
            },
            error: () => {
              this.sugerencia = null;
            }
          });
        }
      },
      error: (e: HttpErrorResponse) => {
        this.errorBusqueda = this.api.mensajeDeError(e);
        this.buscando = false;
      }
    });
  }

  buscarSugerida(): void {
    const s = this.sugerencia;
    if (!s || !s.titulo) return;
    this.buscar(s.titulo);
  }

  limpiarBusqueda(): void {
    this.busqueda = '';
    this.buscado = false;
    this.resultados = [];
    this.errorBusqueda = null;
    this.sugerencia = null;
  }

  guardar(hit: TmdbHit): void {
    if (this.guardandoTmdb[hit.id]) return;
    this.guardandoTmdb[hit.id] = true;
    this.tmdbService.guardar(hit.media_type, hit.id).pipe(takeUntil(this.killed)).subscribe({
      next: (t) => {
        this.navegacion.vinoDeHome = true;
        this.router.navigate(['/publicacion', t.id]).then(() => {
          this.guardandoTmdb[hit.id] = false;
        });
      },
      error: (e: HttpErrorResponse) => {
        this.guardandoTmdb[hit.id] = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
      }
    });
  }
}

function normalizar(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}
