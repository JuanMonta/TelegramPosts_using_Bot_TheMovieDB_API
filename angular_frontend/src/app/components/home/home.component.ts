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
import {
  CatalogoFila,
  Comentario,
  Publicacion,
  SugerenciaHit,
  TmdbHit,
  etiquetaTipo,
  urlPoster
} from '../../models/modelos';

const PASO_VISIBLES = 30;

type FiltroEstado = 'todos' | 'borrador' | 'publicado';

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
  vista: 'lista' | 'grid' = 'lista';
  filasVisibles = PASO_VISIBLES;

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

  constructor(
    private publicacionesService: PublicacionesService,
    private tmdbService: TmdbService,
    private api: ApiService,
    private snack: MatSnackBar,
    private router: Router,
    private navegacion: NavegacionService
  ) {}

  ngOnInit(): void {
    this.cargar();
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

  get visibles(): CatalogoFila[] {
    return this.filtradas().slice(0, this.filasVisibles);
  }

  get hayMas(): boolean {
    return this.filtradas().length > this.filasVisibles;
  }

  get restantes(): number {
    return this.filtradas().length - this.filasVisibles;
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

  cambiarVista(v: 'lista' | 'grid'): void {
    this.vista = v;
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
