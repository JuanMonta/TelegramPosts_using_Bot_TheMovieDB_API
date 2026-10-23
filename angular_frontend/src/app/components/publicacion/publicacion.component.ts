import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { TitulosService } from '../../services/titulos.service';
import { PublicacionesService } from '../../services/publicaciones.service';
import { CaptionTemplateService, CaptionTemplate } from '../../services/caption-template.service';
import { ColaService } from '../../services/cola.service';
import { ApiService } from '../../services/api.service';
import { NavegacionService } from '../../services/navegacion.service';
import { AcoplamientoService } from '../../services/acoplamiento.service';
import { CanalService } from '../../services/canal.service';
import { ConfiguracionService } from '../../services/configuracion.service';
import { ConjuntoTrabajoService } from '../../services/conjunto-trabajo.service';
import { TiraContextoComponent } from '../tira-contexto/tira-contexto.component';
import {
  Acoplamiento,
  ColaItem,
  LinkVideo,
  Publicacion,
  TituloDetalle,
  destinosDeModo,
  etiquetaModo,
  etiquetaTipo,
  nombreMostrable,
  urlPoster
} from '../../models/modelos';

interface FilaLink {
  id?: number;
  texto: string;
  url: string;
  terabox_filename: string;
  orden: number;
}

interface Estreno {
  region: string;
  fechas: Array<{ fecha: string; certificacion: string; tipo: string }>;
}

interface Plataforma {
  region: string;
  proveedores: string[];
}

const REGIONES = ['MX', 'AR', 'CL', 'CO', 'PE'];

const BANDERAS: Record<string, string> = {
  MX: '🇲🇽',
  AR: '🇦🇷',
  CL: '🇨🇱',
  CO: '🇨🇴',
  PE: '🇵🇪'
};

@Component({
  selector: 'app-publicacion',
  templateUrl: './publicacion.component.html',
  styleUrls: ['./publicacion.component.css']
})
export class PublicacionComponent implements OnInit, OnDestroy {
  id = 0;
  cargando = true;
  errorBackend: string | null = null;

  titulo: TituloDetalle | null = null;
  publicacion: Publicacion | null = null;
  estado = 'borrador';

  caption = '';
  estadoGuardado: 'idle' | 'salvando' | 'guardado' = 'idle';

  publicando = false;
  editando = false;
  confirmandoBorrado = false;
  confirmandoRepost = false;
  confirmandoEliminar = false;

  // cola de publicaciones programadas
  colaActiva: ColaItem | null = null;
  programando = false;
  programarPara = '';
  accionCola = false;

  // acoplamientos (el elegido vive en el conjunto de trabajo, visible en la tira)
  acoplamientos: Acoplamiento[] = [];
  @ViewChild('tira') tira?: TiraContextoComponent;

  idCopiado = false;
  private copiarTimer: number | undefined;
  private colaPollTimer: number | undefined;

  disposicion: 'pila' | 'lado' = 'pila';

  links: FilaLink[] = [];
  nuevoTexto = '';
  nuevaUrl = '';
  nuevaTerabox = '';

  plantillas: CaptionTemplate[] = [];
  plantillaSeleccionada: CaptionTemplate | null = null;
  plantillasCargadas = false;
  configurandoPlantillas = false;
  plantillaEditando: CaptionTemplate | null = null;
  nuevaPlantillaNombre = '';
  nuevaPlantillaTexto = '';
  guardandoPlantilla = false;

  // contador de caracteres (límite Telegram photo caption = 1024)
  readonly LIMITE_TELEGRAM = 1024;
  contadorCaption = { total: 0, caption: 0, links: 0, excede: false };

  estrenos: Estreno[] = [];
  plataformas: Plataforma[] = [];

  bandera(region: string): string {
    return BANDERAS[region] ?? '';
  }

  // validación de placeholders en tiempo real
  validacionNueva = { valid: [] as string[], invalid: [] as string[] };
  validacionEditar = { valid: [] as string[], invalid: [] as string[] };

  private killed = new Subject<void>();
  private temporizadorCaption: number | undefined;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private location: Location,
    private titulosService: TitulosService,
    private publicacionesService: PublicacionesService,
    private captionTemplateService: CaptionTemplateService,
    private colaService: ColaService,
    private api: ApiService,
    private snack: MatSnackBar,
    private navegacion: NavegacionService,
    private acoplamientoSvc: AcoplamientoService,
    private canalSvc: CanalService,
    private configSvc: ConfiguracionService,
    private trabajo: ConjuntoTrabajoService
  ) {
    try {
      this.disposicion = localStorage.getItem('disposicion') === 'lado' ? 'lado' : 'pila';
    } catch {
      this.disposicion = 'pila';
    }
  }

  alternarDisposicion(): void {
    this.disposicion = this.disposicion === 'pila' ? 'lado' : 'pila';
    try {
      localStorage.setItem('disposicion', this.disposicion);
    } catch {
      /* no crítico */
    }
  }

  ngOnInit(): void {
    this.route.paramMap.subscribe((pm) => {
      const raw = pm.get('id');
      const nid = raw ? Number(raw) : NaN;
      if (Number.isNaN(nid)) {
        this.errorBackend = 'Identificador no válido.';
        this.cargando = false;
        return;
      }
      this.id = nid;
      this.cargar();
    });
    this.cargarAcoplamientos();
    this.acoplamientoSvc.cambios$.pipe(takeUntil(this.killed))
      .subscribe(() => this.cargarAcoplamientos());
    this.canalSvc.cambios$.pipe(takeUntil(this.killed))
      .subscribe(() => this.cargarAcoplamientos());
  }

  private cargarAcoplamientos(): void {
    this.acoplamientoSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (a) => this.acoplamientos = a,
      error: () => undefined
    });
  }

  ngOnDestroy(): void {
    this.killed.next();
    this.killed.complete();
    if (this.temporizadorCaption !== undefined) {
      window.clearTimeout(this.temporizadorCaption);
    }
    if (this.copiarTimer !== undefined) {
      window.clearTimeout(this.copiarTimer);
    }
    if (this.colaPollTimer !== undefined) {
      window.clearInterval(this.colaPollTimer);
    }
  }

  cargar(): void {
    this.cargando = true;
    this.errorBackend = null;
    this.cargarPlantillas();
    this.titulosService.detalle(this.id).pipe(takeUntil(this.killed)).subscribe({
      next: (t) => {
        this.titulo = t;
        this.links = (t.links ?? [])
          .slice()
          .sort((a, b) => a.orden - b.orden)
          .map((l) => ({ id: l.id, texto: l.texto_boton, url: l.url, terabox_filename: l.terabox_filename ?? '', orden: l.orden }));
        this.estrenos = this.parseEstrenos(t);
        this.plataformas = this.parsePlataformas(t);
        this.asegurarBorrador(t);
      },
      error: (e: HttpErrorResponse) => {
        this.errorBackend = this.api.mensajeDeError(e);
        this.cargando = false;
      }
    });
  }

  /** Tras cambiar REAL/PRUEBA, vuelve a enlazar esta publicación (la de este modo). */
  recargarTrasModo(): void {
    if (this.titulo) this.asegurarBorrador(this.titulo);
  }

  private asegurarBorrador(t: TituloDetalle): void {
    this.publicacionesService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (pubs) => {
        const existente = pubs.find((p) => p.titulo_id === t.id);
        if (existente) {
          this.establecerPublicacion(existente);
        } else {
          const porDefecto = this.captionPorDefecto(t);
          this.publicacionesService.crear(t.id, porDefecto).pipe(takeUntil(this.killed)).subscribe({
            next: (p) => {
              this.establecerPublicacion(p);
              this.snack.open('Borrador creado', 'OK', { duration: 2500 });
            },
            error: (e: HttpErrorResponse) => {
              this.errorBackend = this.api.mensajeDeError(e);
              this.cargando = false;
            }
          });
        }
      },
      error: (e: HttpErrorResponse) => {
        this.errorBackend = this.api.mensajeDeError(e);
        this.cargando = false;
      }
    });
  }

  private establecerPublicacion(p: Publicacion): void {
    this.publicacion = p;
    this.estado = p.estado;
    this.caption = p.caption ?? this.captionPorDefecto(this.titulo!);
    this.cargando = false;
    this.cargarColaActiva();
  }

  // ---- helpers de visualización ----

  nombre(): string {
    return this.titulo ? nombreMostrable(this.titulo) : '';
  }

  original(): string | null {
    return this.titulo ? this.titulo.titulo_original : null;
  }

  tipo(): string {
    return this.titulo ? etiquetaTipo(this.titulo.tipo) : '';
  }

  poster(): string | null {
    return this.titulo ? urlPoster(this.titulo.poster_path, 'w780') : null;
  }

  generos(): string[] {
    return this.titulo && this.titulo.generos
      ? this.titulo.generos.split(',').map((g) => g.trim()).filter(Boolean)
      : [];
  }

  rating(): number | null {
    return this.titulo && this.titulo.rating != null ? this.titulo.rating : null;
  }

  votos(): string | null {
    if (!this.titulo || !this.titulo.vote_count) return null;
    return this.titulo.vote_count.toLocaleString('es-MX');
  }

  fechaLabel(f: string): string {
    try {
      return new Date(f).toLocaleDateString('es-MX', { day: '2-digit', month: 'short', year: 'numeric' });
    } catch {
      return f;
    }
  }

  /** "29 ago 2026 · 19:56" para el pie del preview (fecha + hora local). */
  fmtFechaHora(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    const fecha = this.fechaLabel(iso);
    const hora = d.toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit' });
    return `${fecha} · ${hora}`;
  }

  estrenoTip(e: Estreno): string {
    return e.fechas.map((f) => `${f.tipo} · ${this.fechaLabel(f.fecha)}`).join(' | ');
  }

  private cargarPlantillas(): void {
    this.captionTemplateService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (list) => {
        this.plantillas = list;
        const def = list.find(t => t.isDefault) ?? list[0];
        this.plantillaSeleccionada = def ?? null;
        this.plantillasCargadas = true;
      },
      error: () => {
        this.plantillasCargadas = true;
      }
    });
  }

  placeholder(p: string): string {
    return '{' + p + '}';
  }

  private plantillaPorDefecto(): string {
    return [
      '{title} ({year})',
      '{rating} · {votes} votos',
      '{genres}',
      '',
      '{synopsis}',
      '',
      'Disponible en: {providers}',
      '',
      'Español Latino.',
      '',
      'Contraseña: TeraMoviesMKVFullHD'
    ].join('\n');
  }

  captionPorDefecto(t: TituloDetalle): string {
    const plantilla = this.plantillaSeleccionada?.template ?? this.plantillaPorDefecto();
    const valores: Record<string, string> = {
      title: nombreMostrable(t),
      year: t.anio ? String(t.anio) : '',
      rating: t.rating != null ? t.rating.toFixed(1) : '',
      votes: t.vote_count ? t.vote_count.toLocaleString('es-MX') : '',
      genres: (t.generos ?? '').trim(),
      synopsis: (t.sinopsis ?? '').replace(/\s+/g, ' ').trim(),
      providers: this.proveedoresTexto(t) ?? ''
    };
    return plantilla.replace(/\{(\w+)\}/g, (_m, k) => valores[k] ?? '');
  }

  private proveedoresTexto(t: TituloDetalle): string | null {
    const plataformas = this.parsePlataformas(t);
    if (!plataformas.length) return null;
    return plataformas
      .map(p => {
        const flag = BANDERAS[p.region] ?? '';
        const provs = p.proveedores.slice(0, 3).join(', ');
        return `${flag} ${p.region}: ${provs}`;
      })
      .join('  |  ');
  }

  // ---- diálogo de plantilla ----

  abrirGestionPlantillas(): void {
    this.configurandoPlantillas = true;
    this.plantillaEditando = null;
    this.nuevaPlantillaNombre = '';
    this.nuevaPlantillaTexto = '';
  }

  cerrarGestionPlantillas(): void {
    this.configurandoPlantillas = false;
    this.plantillaEditando = null;
  }

  editarPlantilla(t: CaptionTemplate): void {
    this.plantillaEditando = { ...t };
  }

  cancelarEdicion(): void {
    this.plantillaEditando = null;
  }

  /** Inserta un placeholder en el textarea indicado ('nueva' o 'editar') */
  insertarPlaceholder(destino: 'nueva' | 'editar', name: string): void {
    const token = '{' + name + '}';
    const textarea = destino === 'nueva'
      ? (document.querySelector('.plantilla-nueva-text') as HTMLTextAreaElement)
      : (document.querySelector('.plantilla-editor') as HTMLTextAreaElement);
    if (!textarea) return;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const value = textarea.value;
    textarea.value = value.slice(0, start) + token + value.slice(end);
    textarea.selectionStart = textarea.selectionEnd = start + token.length;
    textarea.focus();
    textarea.dispatchEvent(new Event('input'));
  }

  insertarPlaceholderEnEdicion(name: string): void {
    this.insertarPlaceholder('editar', name);
    this.actualizarValidacionEditar();
  }

  actualizarValidacionNueva(): void {
    this.validacionNueva = this.validarPlaceholders(this.nuevaPlantillaTexto);
  }

  actualizarValidacionEditar(): void {
    this.validacionEditar = this.validarPlaceholders(this.plantillaEditando?.template ?? '');
  }

  /** Detecta placeholders válidos e inválidos en un texto */
  private validarPlaceholders(texto: string): { valid: string[]; invalid: string[] } {
    const validSet = new Set(['{title}', '{year}', '{rating}', '{votes}', '{genres}', '{synopsis}', '{providers}']);
    const found = new Set<string>();
    const invalid = new Set<string>();

    for (let i = 0; i < texto.length; ) {
      if (texto[i] === '{') {
        const end = texto.indexOf('}', i);
        if (end !== -1) {
          const token = texto.slice(i, end + 1);
          if (validSet.has(token)) found.add(token);
          else invalid.add(token);
          i = end + 1;
        } else {
          i++;
        }
      } else {
        i++;
      }
    }
    return { valid: Array.from(found), invalid: Array.from(invalid) };
  }

  guardarEdicionPlantilla(): void {
    const t = this.plantillaEditando;
    if (!t || !t.name.trim() || !t.template.trim()) {
      this.snack.open('Nombre y plantilla son obligatorios', 'OK', { duration: 4000 });
      return;
    }
    this.guardandoPlantilla = true;
    this.captionTemplateService.actualizar(t.id, {
      name: t.name.trim(),
      template: t.template.trim(),
      isDefault: t.isDefault
    }).pipe(takeUntil(this.killed)).subscribe({
      next: (actualizada) => {
        this.recargarPlantillas(actualizada);
        this.plantillaEditando = null;
        this.guardandoPlantilla = false;
        this.aplicarPlantillaAucaption();
        this.snack.open('Plantilla actualizada', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.guardandoPlantilla = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 });
      }
    });
  }

  crearPlantilla(): void {
    const name = this.nuevaPlantillaNombre.trim();
    const template = this.nuevaPlantillaTexto.trim();
    if (!name || !template) {
      this.snack.open('Nombre y plantilla son obligatorios', 'OK', { duration: 4000 });
      return;
    }
    this.guardandoPlantilla = true;
    this.captionTemplateService.crear(name, template, false).pipe(takeUntil(this.killed)).subscribe({
      next: (nueva) => {
        this.recargarPlantillas(nueva);
        this.nuevaPlantillaNombre = '';
        this.nuevaPlantillaTexto = '';
        this.guardandoPlantilla = false;
        this.snack.open('Plantilla creada', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.guardandoPlantilla = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 });
      }
    });
  }

  borrarPlantilla(t: CaptionTemplate): void {
    if (t.isDefault) {
      this.snack.open('No se puede borrar la plantilla por defecto', 'OK', { duration: 4000 });
      return;
    }
    if (!confirm(`¿Borrar plantilla "${t.name}"?`)) return;
    this.captionTemplateService.borrar(t.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.recargarPlantillas();
        this.snack.open('Plantilla eliminada', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 })
    });
  }

  private recargarPlantillas(seleccionar?: CaptionTemplate): void {
    this.captionTemplateService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (list) => {
        this.plantillas = list;
        if (seleccionar) {
          this.plantillaSeleccionada = list.find(p => p.id === seleccionar.id) ?? list[0];
        }
      }
    });
  }

  private aplicarPlantillaAucaption(): void {
    const t = this.titulo;
    if (!t || !this.publicacion) return;
    const nuevo = this.captionPorDefecto(t);
    if (this.caption !== nuevo) {
      this.caption = nuevo;
      this.salvarCaption();
    }
  }

  seleccionarPlantilla(t: CaptionTemplate): void {
    this.plantillaSeleccionada = t;
  }

  marcarComoDefault(t: CaptionTemplate): void {
    if (t.isDefault) return;
    this.guardandoPlantilla = true;
    this.captionTemplateService.actualizar(t.id, { isDefault: true }).pipe(takeUntil(this.killed)).subscribe({
      next: () => this.recargarPlantillas(t),
      error: (e: HttpErrorResponse) => {
        this.guardandoPlantilla = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 });
      }
    });
  }

  private parseEstrenos(t: TituloDetalle): Estreno[] {
    const resultados = (t.datos as unknown as { release_dates?: { results?: unknown[] } })
      ?.release_dates?.results as Array<{ iso_3166_1?: string; release_dates?: Array<{ type?: number; release_date?: string; certification?: string }> }> | undefined;
    if (!resultados) return [];
    const orden = (r: string) => REGIONES.indexOf(r);
    return resultados
      .filter((r) => r.iso_3166_1 && REGIONES.includes(r.iso_3166_1))
      .sort((a, b) => orden(a.iso_3166_1!) - orden(b.iso_3166_1!))
      .map((r) => ({
        region: r.iso_3166_1!,
        fechas: (r.release_dates ?? [])
          .filter((d) => d.release_date && [1, 2, 4, 6].includes(d.type ?? -1))
          .map((d) => ({
            fecha: d.release_date!,
            certificacion: d.certification ?? '',
            tipo: d.type === 1 ? 'estreno' : d.type === 4 ? 'digital' : d.type === 6 ? 'tv' : 'cine'
          }))
      }))
      .filter((e) => e.fechas.length);
  }

  private parsePlataformas(t: TituloDetalle): Plataforma[] {
    const results = (t.datos as unknown as { watch_providers?: { results?: Record<string, { flatrate?: Array<{ provider_name?: string }> }> } })
      ?.watch_providers?.results;
    if (!results) return [];
    const orden = (r: string) => REGIONES.indexOf(r);
    return Object.entries(results)
      .filter(([reg]) => REGIONES.includes(reg))
      .sort((a, b) => orden(a[0]) - orden(b[0]))
      .map(([reg, v]) => ({
        region: reg,
        proveedores: (v.flatrate ?? []).map((p) => p.provider_name ?? '').filter(Boolean)
      }))
      .filter((p) => p.proveedores.length);
  }

  // ---- caption ----

  onCaptionInput(): void {
    this.estadoGuardado = 'salvando';
    this.actualizarContador();
    if (this.temporizadorCaption !== undefined) window.clearTimeout(this.temporizadorCaption);
    this.temporizadorCaption = window.setTimeout(() => this.salvarCaption(), 700);
  }

  private contarGraphemes(s: string): number {
    return s ? [...s].length : 0;
  }

  actualizarContador(): void {
    const captionLen = this.contarGraphemes(this.caption);
    const linksLen = this.links.reduce((acc, l) => acc + this.contarGraphemes(l.texto) + this.contarGraphemes(l.url) + 3, 0);
    const total = captionLen + linksLen;
    this.contadorCaption = {
      total,
      caption: captionLen,
      links: linksLen,
      excede: total > this.LIMITE_TELEGRAM
    };
  }

  trackByLink(_index: number, f: FilaLink): number | string {
    return f.id ?? f.orden;
  }

  trackByPlantilla(_index: number, p: CaptionTemplate): number {
    return p.id;
  }

  trackByEstreno(_index: number, e: Estreno): string {
    return e.region;
  }

  trackByPlataforma(_index: number, p: Plataforma): string {
    return p.region;
  }

  private salvarCaption(): void {
    const pub = this.publicacion;
    if (!pub) return;
    this.publicacionesService.editar(pub.id, this.caption).pipe(takeUntil(this.killed)).subscribe({
      next: (p) => {
        this.publicacion = p;
        this.estadoGuardado = 'guardado';
      },
      error: () => {
        this.estadoGuardado = 'idle';
      }
    });
  }

  // ---- botones ----

  guardarLink(f: FilaLink): void {
    if (!this.titulo || !f.texto.trim() || !f.url.trim()) return;
    if (f.id != null) {
      this.titulosService.editarLink(this.titulo.id, f.id, f.texto.trim(), f.url.trim(), f.orden, f.terabox_filename)
        .pipe(takeUntil(this.killed)).subscribe({
          error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 })
        });
    } else {
      const orden = this.links.length ? Math.max(...this.links.map((l) => l.orden)) + 1 : 0;
      this.titulosService.agregarLink(this.titulo.id, f.texto.trim(), f.url.trim(), orden, f.terabox_filename)
        .pipe(takeUntil(this.killed)).subscribe({
          next: (l) => {
            this.links.push({ id: l.id, texto: l.texto_boton, url: l.url, terabox_filename: l.terabox_filename ?? '', orden: l.orden });
            this.nuevoTexto = '';
            this.nuevaUrl = '';
            this.nuevaTerabox = '';
            this.actualizarContador();
            this.salvarDespuesDeCrear();
          },
          error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 })
        });
    }
  }

  /** Tras crear un botón nuevo el post cambió: fuerza el guardado del caption pendiente. */
  private salvarDespuesDeCrear(): void {
    if (this.temporizadorCaption !== undefined) window.clearTimeout(this.temporizadorCaption);
    this.salvarCaption();
  }

  guardarNuevo(): void {
    const texto = this.nuevoTexto.trim();
    const url = this.nuevaUrl.trim();
    if (!texto || !url) return;
    const orden = this.links.length ? Math.max(...this.links.map((l) => l.orden)) + 1 : 0;
    this.guardarLink({ id: undefined, texto, url, terabox_filename: this.nuevaTerabox.trim(), orden });
  }

  moverLink(f: FilaLink, direccion: -1 | 1): void {
    const i = this.links.indexOf(f);
    const j = i + direccion;
    if (i < 0 || j < 0 || j >= this.links.length || !this.titulo) return;
    const temp = this.links[i].orden;
    this.links[i].orden = this.links[j].orden;
    this.links[j].orden = temp;
    [this.links[i], this.links[j]] = [this.links[j], this.links[i]];
    [this.links[i], this.links[j]].forEach((l) => {
      if (l.id != null) {
        this.titulosService.editarLink(this.titulo!.id, l.id, l.texto, l.url, l.orden, l.terabox_filename)
          .pipe(takeUntil(this.killed)).subscribe({ error: () => undefined });
      }
    });
  }

  eliminarLink(f: FilaLink): void {
    if (!this.titulo || f.id == null) return;
    this.titulosService.eliminarLink(this.titulo.id, f.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.links.splice(this.links.indexOf(f), 1);
        this.actualizarContador();
        this.salvarDespuesDeCrear();
        this.snack.open('Botón eliminado', 'OK', { duration: 2000 });
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 })
    });
  }

  // ---- publicar / editar en canal / repostear / borrar ----

  /** Guarda el caption pendiente (si lo hay) y espera el acuse del backend. */
  private forzarGuardado(): Promise<void> {
    const pub = this.publicacion;
    if (!pub) return Promise.resolve();
    if (this.temporizadorCaption !== undefined) {
      window.clearTimeout(this.temporizadorCaption);
      this.temporizadorCaption = undefined;
    }
    return new Promise((resolve) => {
      this.publicacionesService.editar(pub.id, this.caption).pipe(takeUntil(this.killed)).subscribe({
        next: (actualizada) => {
          this.publicacion = actualizada;
          this.estadoGuardado = 'guardado';
          resolve();
        },
        error: () => {
          this.estadoGuardado = 'idle';
          resolve();
        }
      });
    });
  }

  /** Conjunto elegido o bloqueo con guía cuando hay conjuntos pero ninguno elegido. */
  private conjuntoParaAccion(accion: 'publicar' | 'programar'): number | null {
    const id = this.trabajo.actual;
    if (this.acoplamientos.length && id === null) {
      this.snack.open(`Elige un conjunto en la tira para ${accion}.`, 'OK', { duration: 6000 });
      this.tira?.enfocarSelector();
      return null;
    }
    const ent = this.trabajo.entidad(this.acoplamientos);
    const modo = this.configSvc.modo;
    if (ent && !destinosDeModo(ent.canales, modo).length) {
      this.snack.open(`El conjunto “${ent.nombre}” no tiene destinos de modo ${etiquetaModo(modo)}: agrégalos en Configuración.`, 'OK', { duration: 6000 });
      return null;
    }
    return id;
  }

  irAConfiguracion(): void {
    this.navegacion.abrirCfgAlLlegar = true;
    this.router.navigate(['/']);
  }

  async publicar(): Promise<void> {
    if (!this.publicacion || this.publicando || this.editando) return;
    const conjuntoId = this.conjuntoParaAccion('publicar');
    if (this.acoplamientos.length && conjuntoId === null) return;
    this.publicando = true;
    await this.forzarGuardado();
    const p = this.publicacion;
    if (!p) { this.publicando = false; return; }
    this.publicacionesService.publicar(p.id, conjuntoId).pipe(takeUntil(this.killed)).subscribe({
      next: (listo) => {
        this.publicacion = listo;
        this.estado = listo.estado;
        this.publicando = false;
        if (this.colaActiva) {
          this.colaService.cancelar(this.colaActiva.id).pipe(takeUntil(this.killed)).subscribe({
            next: () => (this.colaActiva = null),
            error: () => (this.colaActiva = null)
          });
        }
        this.snack.open('Publicado en el canal', '✓', { duration: 3000 });
      },
      error: (e: HttpErrorResponse) => {
        this.publicando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        // Puede haber ocurrido que la cola publicó el post en el ínterin.
        this.recargarEstado(false);
      }
    });
  }

  /** Actualiza el post original en el canal (caption + botones), sin repostear. */
  async actualizarEnCanal(): Promise<void> {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.editando = true;
    await this.forzarGuardado();
    const p = this.publicacion;
    if (!p) { this.editando = false; return; }
    this.publicacionesService.editarCanal(p.id).pipe(takeUntil(this.killed)).subscribe({
      next: (actualizada) => {
        this.publicacion = actualizada;
        this.estado = actualizada.estado;
        this.editando = false;
        this.snack.open('Post actualizado en el canal', '✓', { duration: 3000 });
      },
      error: (e: HttpErrorResponse) => {
        this.editando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }

  /** Elimina el post original del canal y envía uno nuevo con los cambios. */
  async repostear(): Promise<void> {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.editando = true;
    this.confirmandoRepost = false;
    await this.forzarGuardado();
    const p = this.publicacion;
    if (!p) { this.editando = false; return; }
    this.publicacionesService.republicar(p.id).pipe(takeUntil(this.killed)).subscribe({
      next: (nueva) => {
        this.publicacion = nueva;
        this.estado = nueva.estado;
        this.editando = false;
        this.snack.open('Post re-publicado en el canal', '✓', { duration: 3000 });
      },
      error: (e: HttpErrorResponse) => {
        this.editando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }

  confirmarRepost(): void {
    this.confirmandoRepost = true;
  }

  cancelarRepost(): void {
    this.confirmandoRepost = false;
  }

  /** Elimina el mensaje del canal y devuelve el registro a borrador (deshacer publicación). */
  async eliminarDelCanal(): Promise<void> {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.editando = true;
    this.confirmandoEliminar = false;
    await this.forzarGuardado();
    const p = this.publicacion;
    if (!p) { this.editando = false; return; }
    this.publicacionesService.eliminarDelCanal(p.id).pipe(takeUntil(this.killed)).subscribe({
      next: (borrador) => {
        this.publicacion = borrador;
        this.estado = borrador.estado;
        this.editando = false;
        this.snack.open('Post eliminado del canal. Volvió a borrador.', '✓', { duration: 3000 });
      },
      error: (e: HttpErrorResponse) => {
        this.editando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }

  confirmarEliminar(): void {
    this.confirmandoEliminar = true;
  }

  cancelarEliminar(): void {
    this.confirmandoEliminar = false;
  }

  // ---- cola de publicaciones programadas ----

  /** Carga el estado de la cola para esta publicación (si está programada). */
  cargarColaActiva(): void {
    if (!this.publicacion) return;
    this.colaService.activoDePublicacion(this.publicacion.id).pipe(takeUntil(this.killed)).subscribe({
      next: (item) => {
        const habiaActiva = !!this.colaActiva;
        this.colaActiva = item;
        this.ajustarPollCola();
        if (habiaActiva && !item) {
          // La cola ya procesó el post (publicado/cancelado) mientras miraba: refrescar UI.
          this.recargarEstado(true);
        }
      },
      error: () => this.ajustarPollCola()
    });
  }

  /**
   * Mientras haya un item activo, vigila cada 15s: si la cola lo publica solo,
   * la UI pasa sola a las acciones del post publicado.
   */
  private ajustarPollCola(): void {
    const activo = !!this.colaActiva;
    if (activo && this.colaPollTimer === undefined) {
      this.colaPollTimer = window.setInterval(() => {
        const pub = this.publicacion;
        if (!pub) return;
        this.cargarColaActiva();
      }, 15000);
    } else if (!activo && this.colaPollTimer !== undefined) {
      window.clearInterval(this.colaPollTimer);
      this.colaPollTimer = undefined;
    }
  }

  /**
   * Recarga el post desde el backend tras un conflicto de la cola (el scheduler
   * publicó el borrador en el ínterin). Así se muestran los botones del post
   * publicado en lugar de los del borrador.
   */
  private recargarEstado(yaPublicado: boolean): void {
    const t = this.titulo;
    if (!t) { this.ajustarPollCola(); return; }
    this.publicacionesService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (pubs) => {
        const existente = pubs.find((p) => p.titulo_id === t.id);
        if (existente) {
          this.establecerPublicacion(existente);
          if (yaPublicado) {
            this.snack.open('El post se publicó desde la cola. Mostrando las acciones de publicación.', 'OK', { duration: 4000 });
          }
        } else {
          this.cargarColaActiva();
        }
      },
      error: () => this.cargarColaActiva()
    });
  }

  /** "hoy 21:00" / "mañana 21:00" / "+1h" para los presets del programador. */
  private soloFecha(d: Date): string {
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${m}-${dd}`;
  }

  private horaStr(d: Date): string {
    const h = String(d.getHours()).padStart(2, '0');
    const mi = String(d.getMinutes()).padStart(2, '0');
    return `${h}:${mi}`;
  }

  setProgramar(horasOffset: number, forzarHora?: number): void {
    const d = new Date();
    d.setTime(d.getTime() + horasOffset * 3600_000);
    if (forzarHora !== undefined) d.setHours(forzarHora, 0, 0, 0);
    this.programarPara = `${this.soloFecha(d)}T${this.horaStr(d)}`;
  }

  abrirProgramacion(): void {
    this.programando = true;
    if (!this.programarPara) this.setProgramar(1);
  }

  cancelarProgramacion(): void {
    this.programando = false;
  }

  confirmarProgramar(): void {
    const pub = this.publicacion;
    if (!pub || !this.programarPara || this.accionCola) return;
    // La programación hereda el conjunto de trabajo.
    const conjuntoId = this.conjuntoParaAccion('programar');
    if (this.acoplamientos.length && conjuntoId === null) return;
    this.accionCola = true;
    this.colaService.programar(pub.id, this.programarPara, conjuntoId).pipe(takeUntil(this.killed)).subscribe({
      next: (item) => {
        this.colaActiva = item;
        this.programando = false;
        this.accionCola = false;
        this.snack.open('Programado en la cola', '✓', { duration: 3000 });
      },
      error: (e: HttpErrorResponse) => {
        this.accionCola = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
      }
    });
  }

  cancelarProgramacionActiva(): void {
    const item = this.colaActiva;
    if (!item || this.accionCola) return;
    this.accionCola = true;
    this.colaService.cancelar(item.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.colaActiva = null;
        this.ajustarPollCola();
        this.accionCola = false;
        this.snack.open('Programación cancelada', '✓', { duration: 2500 });
      },
      error: (e: HttpErrorResponse) => {
        this.accionCola = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        // La cola pudo publicar el post justo antes de cancelar: recargar el estado.
        this.cargarColaActiva();
      }
    });
  }

  /** "29 ago 2026 · 21:00" para mostrar la hora programada. */
  fmtProgramado(iso: string | null | undefined): string {
    if (!iso) return '';
    return this.fmtFechaHora(iso);
  }

  /** Deep link al post en el canal (su hilo de comentarios queda a un clic). */
  get linkComentario(): string | null {
    if (!this.publicacion) return null;
    const canal = this.publicacion.telegram_chat_id;
    const msg = this.publicacion.telegram_message_id;
    if (!canal || !msg) return null;
    const id = canal.startsWith('-100') ? canal.slice(4) : canal.replace(/^-/, '');
    return `https://t.me/c/${id}/${msg}`;
  }

  abrirComentario(): void {
    const link = this.linkComentario;
    if (link) window.open(link, '_blank');
  }

  copiarId(): void {
    const id = this.titulo?.tmdb_id;
    if (id == null) return;
    const texto = String(id);
    const marcarCopiado = () => {
      this.idCopiado = true;
      if (this.copiarTimer !== undefined) window.clearTimeout(this.copiarTimer);
      this.copiarTimer = window.setTimeout(() => (this.idCopiado = false), 1800);
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(texto).then(marcarCopiado).catch(() => this.fallbackCopiar(texto, marcarCopiado));
    } else {
      this.fallbackCopiar(texto, marcarCopiado);
    }
  }

  private fallbackCopiar(texto: string, ok: () => void): void {
    try {
      const ta = document.createElement('textarea');
      ta.value = texto;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      ok();
    } catch {
      /* sin clipboard disponible */
    }
  }

  confirmarBorrado(): void {
    this.confirmandoBorrado = true;
  }

  cancelarBorrado(): void {
    this.confirmandoBorrado = false;
  }

  eliminarTitulo(): void {
    if (!this.titulo) return;
    this.titulosService.eliminar(this.titulo.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => this.irHome(),
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 4000 })
    });
  }

  /**
   * Regresa a la página principal sin apilar entradas '/': si el gestor fue
   * abierto desde home, hace pop en el historial (location.back); si entró por
   * URL directa, reemplaza la entrada actual para que la raíz sea única y no
   * quede nada antes que ella.
   */
  private irHome(): void {
    if (this.navegacion.vinoDeHome) {
      this.navegacion.vinoDeHome = false;
      this.location.back();
    } else {
      this.router.navigate(['/'], { replaceUrl: true });
    }
  }

  volver(): void {
    this.irHome();
  }
}
