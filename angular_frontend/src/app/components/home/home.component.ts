import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil, timeout } from 'rxjs/operators';
import { PublicacionesService } from '../../services/publicaciones.service';
import { TmdbService } from '../../services/tmdb.service';
import { ApiService } from '../../services/api.service';
import { NavegacionService } from '../../services/navegacion.service';
import { ColaService } from '../../services/cola.service';
import { ConfiguracionService, ConfiguracionTelegram } from '../../services/configuracion.service';
import { AcoplamientoService } from '../../services/acoplamiento.service';
import { CanalService } from '../../services/canal.service';
import { BotService } from '../../services/bot.service';
import { ConjuntoTrabajoService } from '../../services/conjunto-trabajo.service';
import {
  Acoplamiento,
  AdminBotDetectado,
  Canal,
  Bot,
  CatalogoFila,
  ColaItem,
  Comentario,
  Publicacion,
  SugerenciaHit,
  TmdbHit,
  destinosDeModo,
  etiquetaModo,
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
  creandoPersonalizado = false;
  // scroll de la lista (sobrevive al ir/volver del editor)
  @ViewChild('paneCuerpo') paneCuerpo!: ElementRef<HTMLElement>;
  scrollLista = 0;
  // diálogo de configuración (para moverle el foco al abrir)
  @ViewChild('cfgCarta') cfgCarta!: ElementRef<HTMLDivElement>;
  @ViewChild('colaCal') colaCal?: ElementRef<HTMLDivElement>;
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
  /** Hora del día elegido en el calendario (el día sale de colaDiaSel: un solo calendario). */
  colaNuevaHora = '';
  colaAccion = false;
  private colaTimer: number | undefined;
  // apartados (En cola / Publicados / Fallos) + calendario de la cola
  colaTab: 'cola' | 'publicados' | 'fallos' = 'cola';
  colaVisibles = PASO_VISIBLES;
  colaCalActivo = false;
  colaDiaSel: string | null = null;
  colaMesAnio = new Date().getFullYear();
  colaMesMes = new Date().getMonth();
  colaBorrando: number | null = null;
  colaConfirmaPurga = false;
  // configuración (modal)
  cfgAbierta = false;
  cfgTab: 'resumen' | 'bots' | 'destinos' | 'conjuntos' | 'datos' | 'ajustes' = 'resumen';
  cfg: ConfiguracionTelegram | null = null;
  cfgCargando = false;
  cfgError: string | null = null;
  cfgGuardando = false;
  cfgErrores: Record<string, string> = {};
  cfgToken = '';
  cfgTokenEditando = false;
  tmdbToken = '';
  tmdbTokenEditando = false;
  cfgIntervaloSeg: number | null = null;
  acoplamientos: Acoplamiento[] = [];
  canales: Canal[] = [];
  bots: Bot[] = [];
  acoplamientoSeleccionado: number | null = null;
  colaNuevoAcoplamiento: number | null = null;
  nuevoCanalNombre = '';
  nuevoCanalChatId = '';
  nuevoCanalTipo: 'real' | 'test' = 'real';
  nuevoCanalBot: number | null = null;
  /** Bot comodín solo para DETECTAR (puede no estar asignado a ningún destino). Se conserva entre sesiones. */
  nuevoCanalBotDetectar: number | null = null;
  deteccionCanal: string | null = null;
  detectandoCanal = false;
  agregandoCanal = false;
  adminBotsDetectados: AdminBotDetectado[] = [];
  adminErrorDetect: string | null = null;
  /** Mínimo de letras para el nombre (igual que el backend: si no llega, se usa el nombre real detectado). */
  readonly NOMBRE_CANAL_MIN = 3;
  readonly NOMBRE_CANAL_MAX = 100;
  /** Enfriamiento entre detecciones (segundos): cada intento usa la API de Telegram. */
  readonly COOLDOWN_DETECCION_S = 10;
  deteccionCooldown = 0;
  private cooldownTimer: number | null = null;
  /** IDs visibles (por defecto ocultos por seguridad). */
  idsVisibles: Record<number, boolean> = {};

  idVisible(id: number): boolean {
    return !!this.idsVisibles[id];
  }

  alternarId(id: number): void {
    this.idsVisibles[id] = !this.idsVisibles[id];
  }

  chatIdEnmascarado(chatId: string): string {
    const s = (chatId || '').trim();
    if (s.length <= 4) return '••••';
    return '•'.repeat(s.length - 4) + s.slice(-4);
  }

  private iniciarCooldownDeteccion(): void {
    this.deteccionCooldown = this.COOLDOWN_DETECCION_S;
    if (this.cooldownTimer !== null) window.clearInterval(this.cooldownTimer);
    this.cooldownTimer = window.setInterval(() => {
      this.deteccionCooldown -= 1;
      if (this.deteccionCooldown <= 0) {
        this.deteccionCooldown = 0;
        if (this.cooldownTimer !== null) {
          window.clearInterval(this.cooldownTimer);
          this.cooldownTimer = null;
        }
      }
    }, 1000);
  }
  nuevoAcoplamientoNombre = '';
  nuevoBotNombre = '';
  nuevoBotToken = '';
  // ---- edición inline (polish: pertenencia visible + datos editables) ----
  botEditando: number | null = null;
  botEditNombre = '';
  botEditToken = '';
  botBorrando: number | null = null;
  botGuardando = false;
  canalEditando: number | null = null;
  canalEditNombre = '';
  canalEditChatId = '';
  canalEditTipo: 'real' | 'test' = 'real';
  canalEditBot: number | null = null;
  canalBorrando: number | null = null;
  canalGuardando = false;
  canalEditDeteccion: string | null = null;
  acopBorrando: number | null = null;
  acopRenombrando: number | null = null;
  acopRenameTxt = '';
  constructor(
    private publicacionesService: PublicacionesService,
    private tmdbService: TmdbService,
    private api: ApiService,
    private snack: MatSnackBar,
    private router: Router,
    private navegacion: NavegacionService,
    private colaService: ColaService,
    private configSvc: ConfiguracionService,
    private acoplamientoSvc: AcoplamientoService,
    private canalSvc: CanalService,
    private botSvc: BotService,
    private trabajo: ConjuntoTrabajoService
  ) {}
  ngOnInit(): void {
    // Viene de la tira del editor: abrir Configuración al llegar.
    if (this.navegacion.abrirCfgAlLlegar) {
      this.navegacion.abrirCfgAlLlegar = false;
      setTimeout(() => this.abrirCfg(), 0);
    }
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
    if (this.cooldownTimer !== null) {
      window.clearInterval(this.cooldownTimer);
      this.cooldownTimer = null;
    }
  }
  cargar(): void {
    this.cargando = true;
    this.errorBackend = null;
    this.publicacionesService.vistaCatalogo().pipe(takeUntil(this.killed)).subscribe({
      next: ({ catalogo, porTitulo }) => {
        this.total = catalogo.total;
        this.borradores = catalogo.borradores;
        this.publicados = catalogo.publicados;
        this.sin = catalogo.sin;
        this.catalogo = catalogo.items;
        this.reseteoPaginado();
        this.porTitulo = porTitulo;
        this.panel = this.elegirPanel();
        this.cargando = false;
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
    this.publicacionesService.vistaCatalogo().pipe(takeUntil(this.killed)).subscribe({
      next: ({ catalogo, porTitulo }) => {
        this.total = catalogo.total;
        this.borradores = catalogo.borradores;
        this.publicados = catalogo.publicados;
        this.sin = catalogo.sin;
        this.catalogo = catalogo.items;
        this.porTitulo = porTitulo;
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
    return this.construirCeldas(this.mesMes, this.mesAnio, this.diaSeleccionado, porClave);
  }
  /** Rejilla de 42 celdas: la usan el calendario del catálogo y el de la cola. */
  private construirCeldas(
    mesActivo: number,
    anioActivo: number,
    seleccionado: string | null,
    porClave: Map<string, { publicados: number; borradores: number; total: number }>
  ): CalCelda[] {
    const primer = new Date(anioActivo, mesActivo, 1);
    const hoyClave = this.fechaClave(new Date());
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
        seleccionado: clave === seleccionado,
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
    // Los posts personalizados no tienen ficha de título: se abre su editor propio.
    if (t.personalizado) {
      this.abrir(t);
      return;
    }
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
  /** Navega al editor del post (de título o personalizado). */
  abrir(t: CatalogoFila): void {
    this.navegacion.vinoDeHome = true;
    if (t.personalizado) {
      this.router.navigate(['/post', t.publicacion_id ?? t.id]);
    } else {
      this.router.navigate(['/publicacion', t.id]);
    }
  }

  /** Crea un post personalizado vacío y abre su editor. */
  crearPersonalizado(): void {
    if (this.creandoPersonalizado) return;
    this.creandoPersonalizado = true;
    this.publicacionesService.crearPersonalizado('').pipe(takeUntil(this.killed)).subscribe({
      next: (p) => {
        this.creandoPersonalizado = false;
        this.navegacion.vinoDeHome = true;
        this.router.navigate(['/post', p.id]);
      },
      error: (e: HttpErrorResponse) => {
        this.creandoPersonalizado = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
      }
    });
  }
  // ---- cola de publicaciones programadas ----
  abrirCola(): void {
    this.colaAbierta = true;
    this.cargarCola();
    // La cola hereda el conjunto de trabajo (editable en su desplegable).
    this.colaNuevoAcoplamiento = this.trabajo.actual;
    this.acoplamientoSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (a) => {
        this.acoplamientos = a;
        if (this.colaNuevoAcoplamiento !== null && !a.some((x) => x.id === this.colaNuevoAcoplamiento)) {
          this.colaNuevoAcoplamiento = null;
        }
      },
      error: () => undefined
    });
    if (this.colaTimer === undefined) {
      this.colaTimer = window.setInterval(() => this.cargarColaSilencioso(), 20000);
    }
  }
  cerrarCola(): void {
    this.colaAbierta = false;
    this.colaBorrando = null;
    this.colaConfirmaPurga = false;
    if (this.colaTimer !== undefined) {
      window.clearInterval(this.colaTimer);
      this.colaTimer = undefined;
    }
    this.refrescarDatos();
  }

  // ---- apartados de la cola (En cola / Publicados / Fallos) ----
  get colaCntCola(): number {
    return this.colaItems.filter((i) => i.estado === 'pendiente' || i.estado === 'publicando').length;
  }
  get colaCntPublicados(): number {
    return this.colaItems.filter((i) => i.estado === 'publicado').length;
  }
  get colaCntFallos(): number {
    return this.colaItems.filter((i) => i.estado === 'fallo' || i.estado === 'cancelado').length;
  }
  elegirColaTab(tab: 'cola' | 'publicados' | 'fallos'): void {
    if (this.colaTab === tab) return;
    this.colaTab = tab;
    this.colaVisibles = PASO_VISIBLES;
    this.colaBorrando = null;
    this.colaConfirmaPurga = false;
  }
  /** Items del apartado activo: pendientes primero, publicados por lo más reciente. */
  get colaApartado(): ColaItem[] {
    const del = (i: ColaItem): boolean =>
      this.colaTab === 'cola'
        ? i.estado === 'pendiente' || i.estado === 'publicando'
        : this.colaTab === 'publicados'
          ? i.estado === 'publicado'
          : i.estado === 'fallo' || i.estado === 'cancelado';
    const lista = this.colaItems.filter(del);
    return this.colaTab === 'publicados'
      ? lista.sort((a, b) => this.colaFechaTexto(b, 'publicado_en', 'programado_para')
          .localeCompare(this.colaFechaTexto(a, 'publicado_en', 'programado_para')))
      : lista.sort((a, b) => (a.programado_para ?? '').localeCompare(b.programado_para ?? ''));
  }
  /** Apartado filtrado por el día elegido en el calendario. El filtro sigue
   *  activo aunque se cierre la ventana flotante (se quita con el chip o "QUITAR DÍA"). */
  get colaFiltrados(): ColaItem[] {
    const base = this.colaApartado;
    if (!this.colaDiaSel) return base;
    return base.filter((i) => {
      const f = this.colaFechaDe(i);
      return f !== null && this.fechaClave(f) === this.colaDiaSel;
    });
  }
  get colaFilas(): ColaItem[] {
    return this.colaFiltrados.slice(0, this.colaVisibles);
  }
  get colaRestantes(): number {
    return this.colaFiltrados.length - this.colaVisibles;
  }
  colaVerMas(): void {
    this.colaVisibles += PASO_VISIBLES;
  }
  /** Clave estable para el *ngFor: sin esto, cada refresco (20s) recreaba los
   *  <li>, reiniciaba su animación (parpadeo) y devolvía el scroll al inicio. */
  trackByColaId(_index: number, item: ColaItem): number {
    return item.id;
  }
  /** Misma idea para las 42 celdas del mes: se reutilizan en vez de recrearse. */
  trackByCeldaClave(_index: number, c: CalCelda): string {
    return c.clave;
  }
  /** Clave estable del catálogo. Sin esto, al cerrar la cola (que refresca el
   *  catálogo) se recreaban todas las filas y se reiniciaba su animación: el
   *  parpadeo de toda la aplicación. */
  trackByCatalogoId(_index: number, t: CatalogoFila): number {
    return t.id;
  }
  private colaFechaTexto(i: ColaItem, ...claves: (keyof ColaItem)[]): string {
    for (const k of claves) {
      const v = i[k];
      if (typeof v === 'string' && v) return v;
    }
    return '';
  }
  /** Fecha de referencia del item: publicación hecha si existe, si no lo programado. */
  private colaFechaDe(i: ColaItem): Date | null {
    const iso = i.estado === 'publicado'
      ? (i.publicado_en ?? i.programado_para ?? '')
      : i.programado_para ?? '';
    if (!iso) return null;
    const d = new Date(iso);
    return isNaN(d.getTime()) ? null : d;
  }

  // ---- calendario de la cola ----
  get colaEtiquetaMes(): string {
    return `${this.MESES[this.colaMesMes]} ${this.colaMesAnio}`;
  }
  get colaEtiquetaDia(): string {
    if (!this.colaDiaSel) return '';
    const [y, m, d] = this.colaDiaSel.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  }
  /** Abre/cierra el único calendario (ventana flotante). El día elegido NO se
   *  borra al cerrarlo: sirve a la vez para filtrar el listado y para programar
   *  la próxima publicación. */
  toggleColaCalendario(): void {
    this.colaCalActivo = !this.colaCalActivo;
    this.colaBorrando = null;
    if (this.colaCalActivo) setTimeout(() => this.colaCal?.nativeElement.focus(), 0);
  }
  /** Cierra el calendario flotante (clic fuera o Esc). */
  cerrarColaCalendario(): void {
    this.colaCalActivo = false;
  }
  colaMesAnterior(): void {
    this.colaMesMes--;
    if (this.colaMesMes < 0) { this.colaMesMes = 11; this.colaMesAnio--; }
    this.colaPreseleccionarMes();
  }
  colaMesSiguiente(): void {
    this.colaMesMes++;
    if (this.colaMesMes > 11) { this.colaMesMes = 0; this.colaMesAnio++; }
    this.colaPreseleccionarMes();
  }
  colaIrHoy(): void {
    const hoy = new Date();
    this.colaMesAnio = hoy.getFullYear();
    this.colaMesMes = hoy.getMonth();
    this.colaDiaSel = this.fechaClave(hoy);
    this.colaVisibles = PASO_VISIBLES;
  }
  /** Al cambiar de mes solo se recarga el contador "ver más": el día elegido
   *  se queda como estaba, porque además de filtrar es el día de programación
   *  y no debe saltar solo al mirar otro mes. */
  private colaPreseleccionarMes(): void {
    this.colaVisibles = PASO_VISIBLES;
  }
  /** 42 celdas del mes activo. Cuenta TODO el historial de la cola (no solo
   *  el apartado abierto): así cualquier día con actividad se ve de un vistazo
   *  en vez de tener que ir adivinando. El filtro de día sí aplica al apartado. */
  colaCeldasMes(): CalCelda[] {
    const mes = this.colaMesMes;
    const anio = this.colaMesAnio;
    const porClave = new Map<string, { publicados: number; borradores: number; total: number }>();
    for (const i of this.colaItems) {
      const f = this.colaFechaDe(i);
      if (!f) continue;
      let e = porClave.get(this.fechaClave(f));
      if (!e) { e = { publicados: 0, borradores: 0, total: 0 }; porClave.set(this.fechaClave(f), e); }
      e.total++;
      if (i.estado === 'publicado') e.publicados++;
      else e.borradores++;
    }
    return this.construirCeldas(mes, anio, this.colaDiaSel, porClave);
  }
  /** Fecha y hora de la nueva programación: el día lo da el calendario (un solo calendario). */
  get colaNuevaFechaIso(): string | null {
    if (!this.colaDiaSel || !/^\d{2}:\d{2}$/.test(this.colaNuevaHora)) return null;
    return `${this.colaDiaSel}T${this.colaNuevaHora}`;
  }
  /** Texto corto del día elegido para el botón del formulario de alta. */
  get colaDiaBoton(): string {
    if (!this.colaDiaSel) return 'Elegir día';
    const [y, m, d] = this.colaDiaSel.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('es-ES', { day: 'numeric', month: 'short', year: 'numeric' });
  }
  /** Conteos del día elegido, calculados en una sola pasada por la cola. */
  get colaDiaConteos(): { cola: number; publicados: number; fallos: number } {
    const conteos = { cola: 0, publicados: 0, fallos: 0 };
    if (!this.colaDiaSel) return conteos;
    for (const i of this.colaItems) {
      const f = this.colaFechaDe(i);
      if (f === null || this.fechaClave(f) !== this.colaDiaSel) continue;
      if (i.estado === 'pendiente' || i.estado === 'publicando') conteos.cola++;
      else if (i.estado === 'publicado') conteos.publicados++;
      else if (i.estado === 'fallo' || i.estado === 'cancelado') conteos.fallos++;
    }
    return conteos;
  }
  /** Los conteos del día si ese día tiene registros fuera del apartado visible; si no, null. */
  get colaDiaEnOtro(): { cola: number; publicados: number; fallos: number } | null {
    if (!this.colaDiaSel) return null;
    const c = this.colaDiaConteos;
    const fuera =
      (this.colaTab !== 'cola' && c.cola > 0) ||
      (this.colaTab !== 'publicados' && c.publicados > 0) ||
      (this.colaTab !== 'fallos' && c.fallos > 0);
    return fuera ? c : null;
  }
  /** Línea resumen del día elegido, armada una sola vez. */
  get resumenDiaCola(): string {
    const { cola, publicados, fallos } = this.colaDiaConteos;
    const pub = publicados === 1 ? 'publicado' : 'publicados';
    const falla = fallos === 1 ? 'fallido' : 'fallidos';
    return `${this.colaEtiquetaDia} · ${cola} en cola · ${publicados} ${pub}`
      + (fallos ? ` · ${fallos} ${falla}` : '');
  }
  colaElegirDia(c: CalCelda): void {
    this.colaDiaSel = this.colaDiaSel === c.clave ? null : c.clave;
    this.colaVisibles = PASO_VISIBLES;
    this.colaBorrando = null;
  }
  colaLimpiarDia(): void {
    this.colaDiaSel = null;
    this.colaVisibles = PASO_VISIBLES;
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
      next: (items) => (this.colaItems = items.sort((a, b) => (a.programado_para ?? '').localeCompare(b.programado_para ?? ''))),
      error: () => undefined
    });
  }
  /** Actualiza la lista de la cola (para el badge del header) sin abrir el modal. */
  private refrescarColaSilencioso(): void {
    this.colaService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (items) => (this.colaItems = items.sort((a, b) => (a.programado_para ?? '').localeCompare(b.programado_para ?? ''))),
      error: () => undefined
    });
  }
  // ---- configuración (modal) ----
  abrirCfg(): void {
    this.cfgAbierta = true;
    this.cfgTab = 'resumen';
    this.cfgErrores = {};
    this.cfgToken = '';
    this.cfgTokenEditando = false;
    this.tmdbToken = '';
    this.tmdbTokenEditando = false;
    this.cfgError = null;
    this.cfgCargando = true;
    setTimeout(() => this.cfgCarta?.nativeElement.focus(), 0);
    this.configSvc.obtener().pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        this.cfg = c;
        this.cfgIntervaloSeg = c.intervalo_cola_ms / 1000;
        this.cfgCargando = false;
        this.configSvc.aplicar(c);
        this.cargarAcoplamientos();
      },
      error: (e: HttpErrorResponse) => {
        this.cfgCargando = false;
        this.cfgError = this.api.mensajeDeError(e);
      }
    });
  }
  cargarAcoplamientos(): void {
    this.acoplamientoSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (a) => this.acoplamientos = a,
      error: () => undefined
    });
    this.canalSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (c) => this.canales = c,
      error: () => undefined
    });
    this.botSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (b) => {
        this.bots = b;
        this.restaurarBotDetectar();
      },
      error: () => undefined
    });
  }
  private static readonly CLAVE_BOT_DETECTAR = 'crow_bot_detectar';

  /** Recupera el bot para detectar guardado; si ya no existe, lo limpia. */
  private restaurarBotDetectar(): void {
    try {
      const raw = localStorage.getItem(HomeComponent.CLAVE_BOT_DETECTAR);
      const id = raw === null ? NaN : Number(raw);
      this.nuevoCanalBotDetectar = !Number.isNaN(id) && this.bots.some((b) => b.id === id) ? id : null;
      if (raw !== null && this.nuevoCanalBotDetectar === null) localStorage.removeItem(HomeComponent.CLAVE_BOT_DETECTAR);
    } catch {
      this.nuevoCanalBotDetectar = null;
    }
  }

  guardarBotDetectar(): void {
    try {
      if (this.nuevoCanalBotDetectar === null) localStorage.removeItem(HomeComponent.CLAVE_BOT_DETECTAR);
      else localStorage.setItem(HomeComponent.CLAVE_BOT_DETECTAR, String(this.nuevoCanalBotDetectar));
    } catch {
      // sin almacenamiento: la selección vive solo en memoria
    }
  }
  agregarBot(): void {
    if (!this.nuevoBotNombre.trim() || !this.nuevoBotToken.trim()) return;
    this.botSvc.crear(this.nuevoBotNombre.trim(), this.nuevoBotToken.trim()).pipe(takeUntil(this.killed)).subscribe({
      next: (b) => {
        this.bots = [...this.bots, b];
        this.nuevoBotNombre = '';
        this.nuevoBotToken = '';
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  eliminarBot(id: number): void {
    this.botSvc.eliminar(id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.bots = this.bots.filter((b) => b.id !== id);
        this.botBorrando = null;
        if (this.nuevoCanalBotDetectar === id) {
          this.nuevoCanalBotDetectar = null;
          this.guardarBotDetectar();
        }
        // los canales que usaban ese bot quedan sin bot: se refrescan en el próximo listar
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  pedirBorrarBot(id: number): void { this.botBorrando = id; this.botEditando = null; }
  cancelarBorrarBot(): void { this.botBorrando = null; }
  empezarEditarBot(b: Bot): void {
    this.botEditando = b.id;
    this.botBorrando = null;
    this.botEditNombre = b.nombre;
    this.botEditToken = '';
  }
  cancelarEditarBot(): void { this.botEditando = null; this.botEditNombre = ''; this.botEditToken = ''; }
  guardarBot(b: Bot): void {
    const nombre = this.botEditNombre.trim();
    const token = this.botEditToken.trim();
    if (!nombre || this.botGuardando) return;
    this.botGuardando = true;
    const datos: { nombre?: string; token?: string } = { nombre };
    if (token) datos.token = token;
    this.botSvc.actualizar(b.id, datos).pipe(takeUntil(this.killed)).subscribe({
      next: (act) => {
        this.bots = this.bots.map((x) => (x.id === act.id ? act : x));
        this.botGuardando = false;
        this.cancelarEditarBot();
        this.snack.open('Bot actualizado', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.botGuardando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }
  nombreBot(id: number | null | undefined): string {
    if (id == null) return 'Sin bot';
    return this.bots.find((b) => b.id === id)?.nombre ?? 'Bot #' + id;
  }
  canalesDeBot(botId: number): Canal[] {
    return this.canales.filter((c) => c.bot_id === botId);
  }
  nombresCanalesDeBot(botId: number): string {
    return this.canalesDeBot(botId).map((c) => c.nombre).join(' · ');
  }
  detectarCanal(): void {
    if (!this.nuevoCanalChatId.trim() || this.detectandoCanal || this.deteccionCooldown > 0) return;
    if (!this.nuevoCanalBotDetectar) {
      this.snack.open('Elige el bot para detectar en su desplegable. Sin bot no hay con qué preguntarle a Telegram.', 'OK', { duration: 6000 });
      return;
    }
    this.detectandoCanal = true;
    this.deteccionCanal = 'Detectando…';
    this.adminBotsDetectados = [];
    this.adminErrorDetect = null;
    this.canalSvc.detectar(this.nuevoCanalChatId.trim(), this.nuevoCanalBotDetectar).pipe(timeout(25000), takeUntil(this.killed)).subscribe({
      next: (d) => {
        this.detectandoCanal = false;
        this.iniciarCooldownDeteccion();
        const real = (d.title || '').trim();
        // Si el nombre está vacío o no llega al mínimo, se completa con el nombre real detectado.
        let relleno = '';
        if (real && this.nuevoCanalNombre.trim().length < this.NOMBRE_CANAL_MIN) {
          this.nuevoCanalNombre = real.slice(0, this.NOMBRE_CANAL_MAX);
          relleno = ' Nombre completado solo.';
        }
        const extra = real ? ` (${real})` : '';
        this.adminBotsDetectados = d.admin_bots || [];
        this.adminErrorDetect = d.admin_error || null;
        const admins = this.adminBotsDetectados.length
          ? ` Encontré ${this.adminBotsDetectados.length} bot${this.adminBotsDetectados.length !== 1 ? 's' : ''} administrador${this.adminBotsDetectados.length !== 1 ? 'es' : ''} abajo.`
          : '';
        this.deteccionCanal = `Es ${d.etiqueta}${extra}.${relleno}${admins} Al añadir se guarda automático.`;
      },
      error: (e: unknown) => {
        this.detectandoCanal = false;
        this.iniciarCooldownDeteccion();
        if (e instanceof HttpErrorResponse) {
          this.deteccionCanal = null;
          this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        } else {
          // Timeout del lado del navegador (25s sin respuesta).
          this.deteccionCanal = null;
          this.snack.open('La detección tardó demasiado (más de 25 segundos). Revisa el ID e inténtalo de nuevo.', 'OK', { duration: 6000 });
        }
      }
    });
  }
  /** Lleva un bot administrador detectado al formulario de Bots para registrarlo con su token. */
  usarAdminBot(ab: AdminBotDetectado): void {
    const base = (ab.nombre || '').trim() || ((ab.username || '').trim() ? '@' + ab.username.trim() : 'Bot');
    this.nuevoBotNombre = base.slice(0, this.NOMBRE_CANAL_MAX);
    this.nuevoBotToken = '';
    this.elegirCfgTab('bots');
    const quien = (ab.username || '').trim() ? '@' + ab.username.trim() : base;
    this.snack.open(`Pega el token de ${quien} (pídelo en @BotFather) y pulsa AÑADIR BOT. Telegram no entrega tokens: solo el dueño del bot lo tiene.`, 'OK', { duration: 9000 });
  }
  agregarCanal(): void {
    if (this.agregandoCanal) return;
    const nombre = this.nuevoCanalNombre.trim();
    if (nombre.length < this.NOMBRE_CANAL_MIN || !this.nuevoCanalChatId.trim()) return;
    this.agregandoCanal = true;
    this.canalSvc.crear(nombre, this.nuevoCanalChatId.trim(), this.nuevoCanalTipo, this.nuevoCanalBot).pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        this.agregandoCanal = false;
        this.canales = [...this.canales, c];
        this.nuevoCanalNombre = '';
        this.nuevoCanalChatId = '';
        this.nuevoCanalBot = null;
        this.deteccionCanal = null;
        this.adminBotsDetectados = [];
        this.adminErrorDetect = null;
      },
      error: (e: HttpErrorResponse) => {
        this.agregandoCanal = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }
  eliminarCanal(id: number): void {
    this.canalSvc.eliminar(id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.canales = this.canales.filter((c) => c.id !== id);
        this.acoplamientos.forEach((a) => a.canales = a.canales.filter((c) => c.id !== id));
        this.canalBorrando = null;
        this.canalEditando = null;
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  pedirBorrarCanal(id: number): void { this.canalBorrando = id; this.canalEditando = null; }
  cancelarBorrarCanal(): void { this.canalBorrando = null; }
  empezarEditarCanal(c: Canal): void {
    this.canalEditando = c.id;
    this.canalBorrando = null;
    this.canalEditNombre = c.nombre;
    this.canalEditChatId = c.chat_id;
    this.canalEditTipo = c.tipo;
    this.canalEditBot = c.bot_id ?? null;
    this.canalEditDeteccion = null;
  }
  cancelarEditarCanal(): void {
    this.canalEditando = null;
    this.canalEditNombre = '';
    this.canalEditChatId = '';
    this.canalEditDeteccion = null;
  }
  canalRedetectando = false;
  redetectarCanalEdit(): void {
    if (!this.canalEditChatId.trim() || this.canalRedetectando || this.deteccionCooldown > 0) return;
    // Usa el bot asignado al destino; si no tiene, el bot comodín para detectar.
    const botId = this.canalEditBot ?? this.nuevoCanalBotDetectar;
    if (!botId) {
      this.snack.open('Elige un bot (asignado o el de detectar) para redetectar. Sin bot no hay con qué preguntarle a Telegram.', 'OK', { duration: 6000 });
      return;
    }
    this.canalRedetectando = true;
    this.canalEditDeteccion = 'Detectando…';
    this.canalSvc.detectar(this.canalEditChatId.trim(), botId).pipe(timeout(25000), takeUntil(this.killed)).subscribe({
      next: (d) => {
        this.canalRedetectando = false;
        this.iniciarCooldownDeteccion();
        const real = (d.title || '').trim();
        let relleno = '';
        if (real && this.canalEditNombre.trim().length < this.NOMBRE_CANAL_MIN) {
          this.canalEditNombre = real.slice(0, this.NOMBRE_CANAL_MAX);
          relleno = ' Nombre completado solo.';
        }
        const extra = real ? ` (${real})` : '';
        this.canalEditDeteccion = `Es ${d.etiqueta}${extra}.${relleno} Se guarda al presionar GUARDAR.`;
      },
      error: (e: unknown) => {
        this.canalRedetectando = false;
        this.iniciarCooldownDeteccion();
        if (e instanceof HttpErrorResponse) {
          this.canalEditDeteccion = null;
          this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        } else {
          this.canalEditDeteccion = null;
          this.snack.open('La detección tardó demasiado (más de 25 segundos). Revisa el ID e inténtalo de nuevo.', 'OK', { duration: 6000 });
        }
      }
    });
  }
  guardarCanal(c: Canal): void {
    const nombre = this.canalEditNombre.trim();
    const chatId = this.canalEditChatId.trim();
    if (nombre.length < this.NOMBRE_CANAL_MIN || !chatId || this.canalGuardando) return;
    this.canalGuardando = true;
    this.canalSvc.actualizar(c.id, {
      nombre,
      chat_id: chatId,
      tipo: this.canalEditTipo,
      bot_id: this.canalEditBot
    }).pipe(takeUntil(this.killed)).subscribe({
      next: (act) => {
        this.canales = this.canales.map((x) => (x.id === act.id ? act : x));
        // refresca la copia embebida dentro de cada acoplamiento
        this.acoplamientos.forEach((a) => {
          a.canales = a.canales.map((m) => (m.id === act.id ? act : m));
        });
        this.canalGuardando = false;
        this.cancelarEditarCanal();
        this.snack.open('Canal actualizado', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.canalGuardando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }
  acoplamientosDeCanal(canalId: number): Acoplamiento[] {
    return this.acoplamientos.filter((a) => a.canales.some((c) => c.id === canalId));
  }
  etiquetaAcoplamientosDeCanal(canalId: number): string {
    const lista = this.acoplamientosDeCanal(canalId);
    if (!lista.length) return 'Sin conjunto';
    return lista.map((a) => a.nombre).join(' · ');
  }
  agregarAcoplamiento(): void {
    if (!this.nuevoAcoplamientoNombre.trim()) return;
    this.acoplamientoSvc.crear(this.nuevoAcoplamientoNombre.trim()).pipe(takeUntil(this.killed)).subscribe({
      next: (a) => {
        this.acoplamientos = [...this.acoplamientos, a];
        this.nuevoAcoplamientoNombre = '';
        this.acoplamientoSeleccionado = a.id;
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  seleccionarAcoplamiento(a: Acoplamiento): void {
    this.acoplamientoSeleccionado = this.acoplamientoSeleccionado === a.id ? null : a.id;
    this.acopAgregarId = null;
  }
  acopAgregarId: number | null = null;
  canalesFueraDe(a: Acoplamiento): Canal[] {
    const dentro = new Set(a.canales.map((c) => c.id));
    return this.canales.filter((c) => !dentro.has(c.id));
  }
  /** Miembros del conjunto de un modo ('real'|'test'), para mostrarlos divididos. */
  miembrosPorModo(a: Acoplamiento, modo: 'real' | 'test'): Canal[] {
    return this.miembrosDe(a).filter((c) => c.tipo === modo);
  }
  /** Destinos aún no agregados, de un modo. */
  faltantesPorModo(a: Acoplamiento, modo: 'real' | 'test'): Canal[] {
    return this.canalesFueraDe(a).filter((c) => c.tipo === modo);
  }
  agregarCanalAcoplamiento(): void {
    if (!this.acoplamientoSeleccionado || !this.acopAgregarId) return;
    const id = this.acopAgregarId;
    this.acopAgregarId = null;
    this.toggleCanalEnAcoplamiento(id);
  }
  eliminarAcoplamiento(id: number): void {
    this.acoplamientoSvc.eliminar(id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.acoplamientos = this.acoplamientos.filter((a) => a.id !== id);
        if (this.acoplamientoSeleccionado === id) this.acoplamientoSeleccionado = null;
        this.acopBorrando = null;
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  pedirBorrarAcoplamiento(id: number): void { this.acopBorrando = id; }
  cancelarBorrarAcoplamiento(): void { this.acopBorrando = null; }
  empezarRenombrarAcoplamiento(a: Acoplamiento): void {
    this.acopRenombrando = a.id;
    this.acopRenameTxt = a.nombre;
    this.acopBorrando = null;
  }
  cancelarRenombrarAcoplamiento(): void { this.acopRenombrando = null; this.acopRenameTxt = ''; }
  guardarNombreAcoplamiento(a: Acoplamiento): void {
    const nombre = this.acopRenameTxt.trim();
    if (!nombre || nombre === a.nombre) { this.cancelarRenombrarAcoplamiento(); return; }
    this.acoplamientoSvc.actualizar(a.id, nombre).pipe(takeUntil(this.killed)).subscribe({
      next: (act) => {
        this.acoplamientos = this.acoplamientos.map((x) => (x.id === act.id ? act : x));
        this.cancelarRenombrarAcoplamiento();
        this.snack.open('Conjunto renombrado', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  miembrosDe(a: Acoplamiento): Canal[] {
    // resuelve contra el catálogo vivo para mostrar bot/tipo actualizados
    return a.canales.map((m) => this.canales.find((c) => c.id === m.id) ?? m);
  }
  nombreAcoplamientoSeleccionado(): string {
    return this.acoplamientos.find((a) => a.id === this.acoplamientoSeleccionado)?.nombre ?? '';
  }
  toggleCanalEnAcoplamiento(canalId: number): void {
    if (!this.acoplamientoSeleccionado) return;
    const acop = this.acoplamientos.find((a) => a.id === this.acoplamientoSeleccionado);
    if (!acop) return;
    const tiene = acop.canales.some((c) => c.id === canalId);
    const obs = tiene
      ? this.acoplamientoSvc.quitarCanal(this.acoplamientoSeleccionado, canalId)
      : this.acoplamientoSvc.agregarCanal(this.acoplamientoSeleccionado, canalId);
    obs.pipe(takeUntil(this.killed)).subscribe({
      next: (actualizado) => {
        this.acoplamientos = this.acoplamientos.map((a) => (a.id === actualizado.id ? actualizado : a));
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 })
    });
  }
  elegirCfgTab(t: 'resumen' | 'bots' | 'destinos' | 'conjuntos' | 'datos' | 'ajustes'): void {
    this.cfgTab = t;
    try { this.cfgCarta?.nativeElement.scrollTo({ top: 0 }); } catch { /* sin scroll: no-op */ }
  }
  cerrarCfg(): void {
    this.cfgAbierta = false;
  }
  reemplazarToken(): void {
    this.cfgTokenEditando = true;
    this.cfgToken = '';
    delete this.cfgErrores['bot_token'];
  }
  cancelarToken(): void {
    this.cfgTokenEditando = false;
    this.cfgToken = '';
    delete this.cfgErrores['bot_token'];
  }
  reemplazarTmdb(): void {
    this.tmdbTokenEditando = true;
    this.tmdbToken = '';
    delete this.cfgErrores['tmdb_token'];
  }
  cancelarTmdb(): void {
    this.tmdbTokenEditando = false;
    this.tmdbToken = '';
    delete this.cfgErrores['tmdb_token'];
  }
  /** El modo se aplica al instante (igual que el toggle del header). */
  modoCambiadoEnCfg(): void {
    this.recargarTrasModo();
    this.configSvc.cargar();
  }
  guardarCfg(): void {
    if (!this.cfg || this.cfgGuardando || this.cfgCargando) return;
    const cfg = this.cfg;
    const errores: Record<string, string> = {};
    if (this.cfgTokenEditando) {
      const t = this.cfgToken.trim();
      if (!t) errores['bot_token'] = 'Pega un token o cancela el reemplazo.';
      else if (t.length > 512) errores['bot_token'] = 'El token es demasiado largo (máx. 512).';
    }
    if (this.tmdbTokenEditando) {
      const t = this.tmdbToken.trim();
      if (!t) errores['tmdb_token'] = 'Pega el token o cancela el reemplazo.';
      else if (t.length > 512) errores['tmdb_token'] = 'El token de TMDB es demasiado largo (máx. 512).';
    }
    const canalValido = (v: string | null): boolean => {
      const s = (v ?? '').trim();
      return s === '' || s.startsWith('@') || /^-?\d{5,25}$/.test(s);
    };
    if (!canalValido(cfg.canal_principal)) {
      errores['canal_principal'] = 'El canal principal debe ser @nombredecanal o un id numérico (ej. -1001234567890).';
    }
    if (!canalValido(cfg.canal_prueba)) {
      errores['canal_prueba'] = 'El canal de prueba debe ser @nombredecanal o un id numérico (ej. -1001234567890).';
    }
    const limite = Number(cfg.limite_caption);
    if (!Number.isInteger(limite) || limite < 1 || limite > 4096) {
      errores['limite_caption'] = 'El máximo del texto debe estar entre 1 y 4096 caracteres.';
    }
    const seg = Number(this.cfgIntervaloSeg);
    if (!Number.isFinite(seg) || seg < 1 || seg > 3600) {
      errores['intervalo_cola_ms'] = 'Cada cuánto revisa la cola debe estar entre 1 segundo y 60 minutos.';
    }
    const colgada = Number(cfg.colgada_minutos);
    if (!Number.isInteger(colgada) || colgada < 1 || colgada > 1440) {
      errores['colgada_minutos'] = 'Los minutos para reintentar publicaciones trabadas deben estar entre 1 y 1440.';
    }
    this.cfgErrores = errores;
    if (Object.keys(errores).length) return;
    this.cfgGuardando = true;
    this.configSvc.guardar({
      bot_token: this.cfgTokenEditando ? this.cfgToken.trim() : null,
      canal_principal: (cfg.canal_principal ?? '').trim(),
      canal_prueba: (cfg.canal_prueba ?? '').trim(),
      tmdb_token: this.tmdbTokenEditando ? this.tmdbToken.trim() : null,
      limite_caption: limite,
      intervalo_cola_ms: Math.round(seg * 1000),
      colgada_minutos: colgada
    }).pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        this.cfgGuardando = false;
        this.cfg = c;
        this.cfgIntervaloSeg = c.intervalo_cola_ms / 1000;
        this.cfgTokenEditando = false;
        this.cfgToken = '';
        this.tmdbTokenEditando = false;
        this.tmdbToken = '';
        this.configSvc.aplicar(c);
        this.snack.open('Configuración guardada', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.cfgGuardando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
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
    const fecha = this.colaNuevaFechaIso;
    if (!this.colaNuevoId || !fecha || this.colaAccion) return;
    const ent = this.colaNuevoAcoplamiento == null
      ? undefined
      : this.acoplamientos.find((a) => a.id === this.colaNuevoAcoplamiento);
    const modo = this.configSvc.modo;
    if (ent && !destinosDeModo(ent.canales, modo).length) {
      this.snack.open(`El conjunto “${ent.nombre}” no tiene destinos de modo ${etiquetaModo(modo)}: agrégalos en Configuración.`, 'OK', { duration: 6000 });
      return;
    }
    this.colaAccion = true;
    this.colaService.programar(this.colaNuevoId, fecha, this.colaNuevoAcoplamiento).pipe(takeUntil(this.killed)).subscribe({
      next: (item) => {
        this.colaItems = [...this.colaItems, item];
        this.colaNuevoId = 0;
        this.colaNuevaHora = '';
        this.colaNuevoAcoplamiento = null;
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
  /** Retira de la cola un item fallido/cancelado (primer toque pide confirmar). */
  eliminarItemCola(item: ColaItem): void {
    if (this.colaAccion) return;
    if (this.colaBorrando !== item.id) {
      this.colaBorrando = item.id;
      return;
    }
    this.colaAccion = true;
    this.colaService.eliminar(item.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.colaAccion = false;
        this.colaBorrando = null;
        this.colaItems = this.colaItems.filter((i) => i.id !== item.id);
        this.snack.open('Retirado de la cola', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.colaAccion = false;
        this.colaBorrando = null;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        this.cargarColaSilencioso();
      }
    });
  }
  cancelarBorrarItemCola(): void {
    this.colaBorrando = null;
  }
  /** Vacía el apartado abierto (los publicados se pueden purgar también por historia). */
  purgarApartadoCola(): void {
    if (this.colaAccion) return;
    if (!this.colaConfirmaPurga) {
      this.colaConfirmaPurga = true;
      return;
    }
    const estados = this.colaTab === 'publicados' ? ['publicado'] : ['fallo', 'cancelado'];
    this.colaAccion = true;
    this.colaService.purgar(estados).pipe(takeUntil(this.killed)).subscribe({
      next: (r) => {
        this.colaAccion = false;
        this.colaConfirmaPurga = false;
        this.cargarColaSilencioso();
        this.snack.open(
          r.borrados === 1 ? '1 registro borrado' : `${r.borrados} registros borrados`,
          '✓', { duration: 2500 }
        );
      },
      error: (e: HttpErrorResponse) => {
        this.colaAccion = false;
        this.colaConfirmaPurga = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }
  cancelarPurgarCola(): void {
    this.colaConfirmaPurga = false;
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
  poster(t: { poster_path: string | null; miniatura_url?: string | null }): string | null {
    return t.miniatura_url ?? urlPoster(t.poster_path);
  }
  posterUrl(p: string | null): string | null {
    return urlPoster(p);
  }
  tipo(t: 'movie' | 'tv' | 'personalizado'): string {
    if (t === 'personalizado') return 'Personalizado';
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
