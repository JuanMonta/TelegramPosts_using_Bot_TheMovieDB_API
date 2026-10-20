import { Component, HostListener, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { finalize, takeUntil } from 'rxjs/operators';
import { PublicacionesService } from '../../services/publicaciones.service';
import { TmdbService } from '../../services/tmdb.service';
import { CaptionTemplateService, CaptionTemplate } from '../../services/caption-template.service';
import { ColaService } from '../../services/cola.service';
import { ApiService } from '../../services/api.service';
import { NavegacionService } from '../../services/navegacion.service';
import { AcoplamientoService } from '../../services/acoplamiento.service';
import { ConfiguracionService } from '../../services/configuracion.service';
import { ConjuntoTrabajoService } from '../../services/conjunto-trabajo.service';
import { TiraContextoComponent } from '../tira-contexto/tira-contexto.component';
import {
  Acoplamiento,
  ColaItem,
  Publicacion,
  PublicacionMedia,
  TmdbHit,
  TmdbImagen,
  destinosDeModo,
  etiquetaModo
} from '../../models/modelos';

interface FilaLink {
  id?: number;
  texto: string;
  url: string;
  terabox_filename: string;
  orden: number;
}

@Component({
  selector: 'app-post',
  templateUrl: './post.component.html',
  styleUrls: ['./post.component.css']
})
export class PostComponent implements OnInit, OnDestroy {
  id = 0;
  cargando = true;
  errorBackend: string | null = null;

  publicacion: Publicacion | null = null;
  estado = 'borrador';

  caption = '';
  estadoGuardado: 'idle' | 'salvando' | 'guardado' = 'idle';

  imagenes: PublicacionMedia[] = [];
  subiendo = 0;
  arrastrando: number | null = null;
  dropDestino: number | null = null;
  sobreZonaPegado = false;
  arrastrandoArchivos = false;
  pegadoReciente = false;

  // selector de imágenes de TMDB
  tmdbAbierto = false;
  tmdbQuery = '';
  tmdbBuscando = false;
  tmdbResultados: TmdbHit[] = [];
  tmdbTituloSel: TmdbHit | null = null;
  tmdbCargandoImagenes = false;
  tmdbImagenes: TmdbImagen[] = [];
  tmdbAdjuntando = 0;

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
  validacionNueva = { valid: [] as string[], invalid: [] as string[] };
  validacionEditar = { valid: [] as string[], invalid: [] as string[] };

  publicando = false;
  editando = false;
  confirmandoRepost = false;
  confirmandoEliminar = false;
  confirmandoQuitarCanal = false;

  colaActiva: ColaItem | null = null;
  programando = false;
  programarPara = '';
  accionCola = false;

  acoplamientos: Acoplamiento[] = [];
  @ViewChild('tira') tira?: TiraContextoComponent;

  readonly LIMITE_FOTO = 1024;
  readonly LIMITE_TEXTO = 4096;
  contadorCaption = { total: 0, caption: 0, links: 0, excede: false, cerca: false, restante: 4096, limite: 4096 };

  private killed = new Subject<void>();
  private temporizadorCaption: number | undefined;
  private temporizadorPegado: number | undefined;
  private temporizadorOrden: number | undefined;
  private colaPollTimer: number | undefined;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private location: Location,
    private publicacionesService: PublicacionesService,
    private tmdb: TmdbService,
    private captionTemplateService: CaptionTemplateService,
    private colaService: ColaService,
    private api: ApiService,
    private snack: MatSnackBar,
    private navegacion: NavegacionService,
    private acoplamientoSvc: AcoplamientoService,
    private configSvc: ConfiguracionService,
    private trabajo: ConjuntoTrabajoService
  ) {}

  ngOnInit(): void {
    this.route.paramMap.subscribe((pm) => {
      const nid = Number(pm.get('id'));
      if (Number.isNaN(nid)) {
        this.errorBackend = 'Identificador no válido.';
        this.cargando = false;
        return;
      }
      this.id = nid;
      this.cargar();
    });
    this.acoplamientoSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (a) => (this.acoplamientos = a),
      error: () => undefined
    });
    this.cargarPlantillas();
  }

  ngOnDestroy(): void {
    if (this.temporizadorCaption !== undefined) {
      window.clearTimeout(this.temporizadorCaption);
      this.temporizadorCaption = undefined;
      this.salvarCaption(true);
    }
    if (this.temporizadorOrden !== undefined) {
      window.clearTimeout(this.temporizadorOrden);
      this.temporizadorOrden = undefined;
      this.enviarOrden(true);
    }
    if (this.temporizadorPegado !== undefined) window.clearTimeout(this.temporizadorPegado);
    if (this.colaPollTimer !== undefined) window.clearInterval(this.colaPollTimer);
    this.killed.next();
    this.killed.complete();
  }

  cargar(): void {
    this.cargando = true;
    this.errorBackend = null;
    this.publicacionesService.detalle(this.id).pipe(takeUntil(this.killed)).subscribe({
      next: (p) => {
        this.publicacion = p;
        this.estado = p.estado;
        this.caption = p.caption ?? '';
        this.actualizarContador();
        this.cargando = false;
        this.cargarImagenes();
        this.cargarLinks();
        this.cargarColaActiva();
      },
      error: (e: HttpErrorResponse) => {
        this.errorBackend = this.api.mensajeDeError(e);
        this.cargando = false;
      }
    });
  }

  // ---- imágenes ----

  cargarImagenes(): void {
    this.publicacionesService.media(this.id).pipe(takeUntil(this.killed)).subscribe({
      next: (m) => {
        this.imagenes = m;
        this.actualizarContador();
      },
      error: () => undefined
    });
  }

  subirArchivos(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    this.subirLote(files);
  }

  @HostListener('window:paste', ['$event'])
  alPegar(ev: ClipboardEvent): void {
    if (this.cargando || this.errorBackend) return;
    const datos = ev.clipboardData;
    if (!datos) return;
    const files: File[] = [];
    for (let i = 0; i < datos.items.length; i++) {
      const it = datos.items[i];
      if (it.kind === 'file' && it.type.startsWith('image/')) {
        const f = it.getAsFile();
        if (f) files.push(this.nombrePegado(f, files.length));
      }
    }
    if (!files.length) return;
    ev.preventDefault();
    if (this.imagenes.length < 10) this.marcarPegado();
    this.subirLote(files);
  }

  private marcarPegado(): void {
    this.pegadoReciente = true;
    if (this.temporizadorPegado !== undefined) window.clearTimeout(this.temporizadorPegado);
    this.temporizadorPegado = window.setTimeout(() => {
      this.pegadoReciente = false;
      this.temporizadorPegado = undefined;
    }, 1000);
  }

  // ---- arrastrar archivos desde la PC ----

  private esArrastreDeArchivos(ev: DragEvent): boolean {
    const tipos = ev.dataTransfer?.types;
    return !!tipos && Array.from(tipos).includes('Files');
  }

  alEntrarArchivos(ev: DragEvent): void {
    if (!this.esArrastreDeArchivos(ev)) return;
    ev.preventDefault();
    this.arrastrandoArchivos = true;
  }

  alPasarArchivos(ev: DragEvent): void {
    if (!this.esArrastreDeArchivos(ev)) return;
    ev.preventDefault();
    if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'copy';
    this.arrastrandoArchivos = true;
  }

  alSalirArchivos(ev: DragEvent): void {
    if (!this.arrastrandoArchivos) return;
    const el = ev.currentTarget as HTMLElement | null;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const fuera = ev.clientX < r.left || ev.clientX >= r.right || ev.clientY < r.top || ev.clientY >= r.bottom;
    if (fuera) this.arrastrandoArchivos = false;
  }

  alSoltarArchivos(ev: DragEvent): void {
    if (!this.esArrastreDeArchivos(ev)) return;
    ev.preventDefault();
    this.arrastrandoArchivos = false;
    const todos = Array.from(ev.dataTransfer?.files ?? []);
    const files = todos.filter((f) => f.type.startsWith('image/'));
    if (!files.length) {
      this.snack.open('Suelta archivos de imagen (JPG, PNG, WEBP…).', 'OK', { duration: 4000 });
      return;
    }
    if (files.length < todos.length) {
      this.snack.open('Se ignoraron los archivos que no son imágenes.', 'OK', { duration: 4000 });
    }
    this.subirLote(files);
  }

  private nombrePegado(f: File, n: number): File {
    let ext = (f.type.split('/')[1] || 'png').toLowerCase();
    if (ext === 'jpeg') ext = 'jpg';
    if (ext === 'svg+xml') ext = 'svg';
    const d = new Date();
    const p = (v: number) => String(v).padStart(2, '0');
    const sello = `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
    return new File([f], `pegado-${sello}-${n + 1}.${ext}`, { type: f.type });
  }

  private subirLote(files: File[]): void {
    if (!files.length) return;
    const libres = 10 - this.imagenes.length;
    if (libres <= 0) {
      this.snack.open('Ya hay 10 imágenes (el máximo de Telegram).', 'OK', { duration: 4000 });
      return;
    }
    const lote = files.slice(0, libres);
    if (files.length > libres) {
      this.snack.open(`Solo se agregarán ${libres} (máximo 10).`, 'OK', { duration: 4000 });
    }
    for (const f of lote) {
      this.subiendo++;
      this.publicacionesService.subirImagen(this.id, f).pipe(takeUntil(this.killed)).subscribe({
        next: (m) => {
          this.imagenes.push(m);
          this.subiendo--;
          this.actualizarContador();
        },
        error: (e: HttpErrorResponse) => {
          this.subiendo--;
          this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 6000 });
        }
      });
    }
  }

  quitarImagen(m: PublicacionMedia): void {
    if (!confirm('¿Quitar esta imagen del post?')) return;
    this.publicacionesService.borrarMedia(this.id, m.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.imagenes = this.imagenes.filter((x) => x.id !== m.id);
        this.actualizarContador();
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 })
    });
  }

  moverImagen(m: PublicacionMedia, dir: -1 | 1): void {
    const i = this.imagenes.indexOf(m);
    const j = i + dir;
    if (i < 0 || j < 0 || j >= this.imagenes.length) return;
    const arr = [...this.imagenes];
    [arr[i], arr[j]] = [arr[j], arr[i]];
    this.imagenes = arr;
    this.guardarOrden();
  }

  onDragStart(i: number, ev: DragEvent): void {
    this.arrastrando = i;
    this.dropDestino = i;
    if (ev.dataTransfer) {
      ev.dataTransfer.effectAllowed = 'move';
      ev.dataTransfer.setData('text/plain', String(i));
    }
  }

  onDragOver(i: number, ev: DragEvent): void {
    if (this.arrastrando === null) return;
    ev.preventDefault();
    if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move';
    this.dropDestino = i;
  }

  onDragLeave(i: number, ev: DragEvent): void {
    const el = ev.currentTarget as HTMLElement | null;
    const hacia = ev.relatedTarget as Node | null;
    if (el && hacia && el.contains(hacia)) return;
    if (this.dropDestino === i) this.dropDestino = null;
  }

  onDrop(i: number, ev: DragEvent): void {
    if (this.arrastrando === null) return;
    ev.preventDefault();
    const desde = this.arrastrando;
    this.limpiarArrastre();
    if (desde === i) return;
    const arr = [...this.imagenes];
    const [mov] = arr.splice(desde, 1);
    arr.splice(i, 0, mov);
    this.imagenes = arr;
    this.guardarOrden();
  }

  onDragEnd(): void {
    this.limpiarArrastre();
  }

  private limpiarArrastre(): void {
    this.arrastrando = null;
    this.dropDestino = null;
  }

  trackByImagen(_i: number, m: PublicacionMedia): number {
    return m.id;
  }

  /** Evita parpadeos: agrupa los movimientos seguidos en un solo guardado. */
  private guardarOrden(): void {
    if (this.temporizadorOrden !== undefined) window.clearTimeout(this.temporizadorOrden);
    this.temporizadorOrden = window.setTimeout(() => {
      this.temporizadorOrden = undefined;
      this.enviarOrden();
    }, 220);
  }

  /** No reemplaza la lista con la respuesta: el orden local ya es el correcto. */
  private enviarOrden(sinCancelar = false): void {
    const ids = this.imagenes.map((x) => x.id);
    const peticion = this.publicacionesService.ordenarMedia(this.id, ids);
    (sinCancelar ? peticion : peticion.pipe(takeUntil(this.killed))).subscribe({
      next: () => undefined,
      error: (e: HttpErrorResponse) => {
        if (sinCancelar) return;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
        this.cargarImagenes();
      }
    });
  }

  // ---- selector TMDB ----

  abrirTmdb(): void {
    this.tmdbAbierto = true;
    this.tmdbTituloSel = null;
    this.tmdbImagenes = [];
  }

  cerrarTmdb(): void {
    this.tmdbAbierto = false;
  }

  buscarTmdb(): void {
    const q = this.tmdbQuery.trim();
    if (!q || this.tmdbBuscando) return;
    this.tmdbBuscando = true;
    this.tmdbTituloSel = null;
    this.tmdbImagenes = [];
    this.tmdb.buscar(q).pipe(takeUntil(this.killed)).subscribe({
      next: (r) => {
        this.tmdbResultados = r;
        this.tmdbBuscando = false;
      },
      error: (e: HttpErrorResponse) => {
        this.tmdbBuscando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
      }
    });
  }

  elegirTmdb(h: TmdbHit): void {
    this.tmdbTituloSel = h;
    this.tmdbCargandoImagenes = true;
    this.tmdb.imagenes(h.media_type, h.id).pipe(takeUntil(this.killed)).subscribe({
      next: (img) => {
        this.tmdbImagenes = img;
        this.tmdbCargandoImagenes = false;
      },
      error: (e: HttpErrorResponse) => {
        this.tmdbCargandoImagenes = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
      }
    });
  }

  urlTmdb(path: string): string {
    return `https://image.tmdb.org/t/p/w185${path}`;
  }

  adjuntarTmdb(img: TmdbImagen): void {
    if (this.imagenes.length >= 10) {
      this.snack.open('Ya hay 10 imágenes (el máximo de Telegram).', 'OK', { duration: 4000 });
      return;
    }
    const tipo = this.tmdbTituloSel?.media_type ?? 'movie';
    this.tmdbAdjuntando++;
    this.publicacionesService.adjuntarTmdb(this.id, tipo, img.file_path).pipe(takeUntil(this.killed)).subscribe({
      next: (m) => {
        this.tmdbAdjuntando--;
        this.imagenes.push(m);
        this.actualizarContador();
        this.snack.open('Imagen de TMDB agregada', '✓', { duration: 2000 });
      },
      error: (e: HttpErrorResponse) => {
        this.tmdbAdjuntando--;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
      }
    });
  }

  // ---- enlaces ----

  cargarLinks(): void {
    this.publicacionesService.links(this.id).pipe(takeUntil(this.killed)).subscribe({
      next: (ls) => {
        this.links = (ls ?? [])
          .slice()
          .sort((a, b) => (a.orden ?? 0) - (b.orden ?? 0))
          .map((l) => ({ id: l.id, texto: l.texto_boton, url: l.url, terabox_filename: l.terabox_filename ?? '', orden: l.orden }));
        this.actualizarContador();
      },
      error: () => undefined
    });
  }

  guardarNuevoLink(): void {
    const texto = this.nuevoTexto.trim();
    const url = this.nuevaUrl.trim();
    if (!texto || !url) return;
    const terabox = this.nuevaTerabox.trim();
    const orden = this.links.length ? Math.max(...this.links.map((l) => l.orden)) + 1 : 0;
    this.publicacionesService.agregarLink(this.id, texto, url, orden, terabox).pipe(takeUntil(this.killed)).subscribe({
      next: (l) => {
        this.links.push({ id: l.id, texto: l.texto_boton, url: l.url, terabox_filename: l.terabox_filename ?? '', orden: l.orden });
        this.nuevoTexto = '';
        this.nuevaUrl = '';
        this.nuevaTerabox = '';
        this.actualizarContador();
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 })
    });
  }

  guardarLink(f: FilaLink): void {
    if (f.id == null) return;
    if (!f.texto.trim() || !f.url.trim()) return;
    this.publicacionesService.editarLink(this.id, f.id, f.texto.trim(), f.url.trim(), f.orden, f.terabox_filename)
      .pipe(takeUntil(this.killed)).subscribe({
        next: () => this.actualizarContador(),
        error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 })
      });
  }

  moverLink(f: FilaLink, dir: -1 | 1): void {
    const i = this.links.indexOf(f);
    const j = i + dir;
    if (i < 0 || j < 0 || j >= this.links.length) return;
    const temp = this.links[i].orden;
    this.links[i].orden = this.links[j].orden;
    this.links[j].orden = temp;
    [this.links[i], this.links[j]] = [this.links[j], this.links[i]];
    [this.links[i], this.links[j]].forEach((l) => {
      if (l.id != null) {
        this.publicacionesService.editarLink(this.id, l.id, l.texto, l.url, l.orden, l.terabox_filename)
          .pipe(takeUntil(this.killed)).subscribe({ error: () => undefined });
      }
    });
  }

  eliminarLink(f: FilaLink): void {
    if (f.id == null) return;
    this.publicacionesService.eliminarLink(this.id, f.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => {
        this.links.splice(this.links.indexOf(f), 1);
        this.actualizarContador();
      },
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 })
    });
  }

  // ---- plantillas ----

  private cargarPlantillas(): void {
    this.captionTemplateService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (list) => {
        this.plantillas = list;
        const def = list.find((t) => t.isDefault) ?? list[0];
        this.plantillaSeleccionada = this.plantillaSeleccionada ?? def ?? null;
        this.plantillasCargadas = true;
      },
      error: () => {
        this.plantillasCargadas = true;
      }
    });
  }

  /** Copia el texto de la plantilla elegida al post. */
  usarPlantilla(): void {
    const t = this.plantillaSeleccionada;
    if (!t) return;
    if (this.caption.trim() && !confirm('¿Reemplazar el texto actual por la plantilla?')) return;
    this.caption = t.template;
    this.onCaptionInput();
  }

  placeholder(p: string): string {
    return '{' + p + '}';
  }

  // ---- diálogo de plantillas ----

  abrirGestionPlantillas(): void {
    this.configurandoPlantillas = true;
    this.plantillaEditando = null;
    this.nuevaPlantillaNombre = '';
    this.nuevaPlantillaTexto = '';
    this.validacionNueva = { valid: [], invalid: [] };
  }

  cerrarGestionPlantillas(): void {
    this.configurandoPlantillas = false;
    this.plantillaEditando = null;
  }

  editarPlantilla(t: CaptionTemplate): void {
    this.plantillaEditando = { ...t };
    this.actualizarValidacionEditar();
  }

  cancelarEdicion(): void {
    this.plantillaEditando = null;
  }

  /** Inserta un placeholder en el textarea indicado ('nueva' o 'editar'). */
  insertarPlaceholder(destino: 'nueva' | 'editar', name: string): void {
    const token = '{' + name + '}';
    const textarea = (destino === 'nueva'
      ? document.querySelector('.plantilla-nueva-text')
      : document.querySelector('.plantilla-editor')) as HTMLTextAreaElement | null;
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

  actualizarValidacionNueva(ev?: any): void {
    const texto = (ev?.target?.value ?? this.nuevaPlantillaTexto) || '';
    this.validacionNueva = this.validarPlaceholders(texto);
  }

  actualizarValidacionEditar(ev?: any): void {
    const texto = (ev?.target?.value ?? this.plantillaEditando?.template) || '';
    this.validacionEditar = this.validarPlaceholders(texto);
  }

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
        } else i++;
      } else i++;
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
        this.validacionNueva = { valid: [], invalid: [] };
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
          this.plantillaSeleccionada = list.find((p) => p.id === seleccionar.id) ?? list[0] ?? null;
        }
      }
    });
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

  // ---- caption ----

  limite(): number {
    return this.imagenes.length > 0 ? this.LIMITE_FOTO : this.LIMITE_TEXTO;
  }

  onCaptionInput(): void {
    this.estadoGuardado = 'salvando';
    this.actualizarContador();
    if (this.temporizadorCaption !== undefined) window.clearTimeout(this.temporizadorCaption);
    this.temporizadorCaption = window.setTimeout(() => this.salvarCaption(), 700);
  }

  private contar(s: string): number {
    return s ? [...s].length : 0;
  }

  actualizarContador(): void {
    const captionLen = this.contar(this.caption);
    const linksLen = this.links.reduce((acc, l) => acc + this.contar(l.texto) + this.contar(l.url) + 3, 0);
    const total = captionLen + linksLen;
    const limite = this.limite();
    const restante = limite - total;
    const cerca = restante >= 0 && restante <= Math.max(40, Math.round(limite * 0.1));
    this.contadorCaption = { total, caption: captionLen, links: linksLen, excede: total > limite, cerca, restante, limite };
  }

  private salvarCaption(alDestruir = false): void {
    const pub = this.publicacion;
    if (!pub) return;
    const peticion = this.publicacionesService.editar(pub.id, this.caption);
    (alDestruir ? peticion : peticion.pipe(takeUntil(this.killed))).subscribe({
      next: (p) => {
        this.publicacion = p;
        this.estadoGuardado = 'guardado';
      },
      error: (e: HttpErrorResponse) => {
        this.estadoGuardado = 'idle';
        this.snack.open(`No se pudo guardar el texto: ${this.api.mensajeDeError(e)}`, 'OK', { duration: 6000 });
      }
    });
  }

  /** Fuerza el guardado pendiente. Devuelve false si falló, para que la acción NO publique el texto viejo. */
  private forzarGuardado(): Promise<boolean> {
    const pub = this.publicacion;
    if (!pub) return Promise.resolve(false);
    if (this.temporizadorCaption !== undefined) {
      window.clearTimeout(this.temporizadorCaption);
      this.temporizadorCaption = undefined;
    }
    return new Promise<boolean>((resolve) => {
      this.publicacionesService.editar(pub.id, this.caption).pipe(
        finalize(() => resolve(this.estadoGuardado === 'guardado')),
        takeUntil(this.killed)
      ).subscribe({
        next: (actualizada) => {
          this.publicacion = actualizada;
          this.estadoGuardado = 'guardado';
        },
        error: (e: HttpErrorResponse) => {
          this.estadoGuardado = 'idle';
          this.snack.open(`No se pudo guardar el texto: ${this.api.mensajeDeError(e)}`, 'OK', { duration: 6000 });
        }
      });
    });
  }

  // ---- acciones ----

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
    if (!(await this.forzarGuardado())) {
      this.publicando = false;
      return;
    }
    const p = this.publicacion;
    if (!p) {
      this.publicando = false;
      return;
    }
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
      }
    });
  }

  async actualizarEnCanal(): Promise<void> {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.editando = true;
    if (!(await this.forzarGuardado())) {
      this.editando = false;
      return;
    }
    const p = this.publicacion;
    if (!p) {
      this.editando = false;
      return;
    }
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

  async repostear(): Promise<void> {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.editando = true;
    this.confirmandoRepost = false;
    if (!(await this.forzarGuardado())) {
      this.editando = false;
      return;
    }
    const p = this.publicacion;
    if (!p) {
      this.editando = false;
      return;
    }
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

  async eliminarDelCanal(): Promise<void> {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.editando = true;
    this.confirmandoQuitarCanal = false;
    if (!(await this.forzarGuardado())) {
      this.editando = false;
      return;
    }
    const p = this.publicacion;
    if (!p) {
      this.editando = false;
      return;
    }
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

  eliminarPost(): void {
    if (!this.publicacion || this.editando || this.publicando) return;
    this.confirmandoEliminar = false;
    this.publicacionesService.eliminarPost(this.publicacion.id).pipe(takeUntil(this.killed)).subscribe({
      next: () => this.irHome(),
      error: (e: HttpErrorResponse) => this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 })
    });
  }

  // ---- cola ----

  cargarColaActiva(): void {
    if (!this.publicacion) return;
    this.colaService.activoDePublicacion(this.publicacion.id).pipe(takeUntil(this.killed)).subscribe({
      next: (item) => {
        this.colaActiva = item;
        this.ajustarPollCola();
      },
      error: () => this.ajustarPollCola()
    });
  }

  private ajustarPollCola(): void {
    const activo = !!this.colaActiva;
    if (activo && this.colaPollTimer === undefined) {
      this.colaPollTimer = window.setInterval(() => this.cargarColaActiva(), 15000);
    } else if (!activo && this.colaPollTimer !== undefined) {
      window.clearInterval(this.colaPollTimer);
      this.colaPollTimer = undefined;
    }
  }

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
        this.cargarColaActiva();
      }
    });
  }

  fmtFechaHora(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return `${d.toLocaleDateString('es-MX', { day: '2-digit', month: 'short', year: 'numeric' })} · ${d.toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit' })}`;
  }

  get resumen(): string {
    const n = this.imagenes.length;
    if (n === 0) return 'Solo texto';
    if (n === 1) return '1 imagen';
    return `${n} imágenes`;
  }

  /** Imágenes que se muestran en la vista previa (máx. 4, como Telegram). */
  get albumPreview(): PublicacionMedia[] {
    return this.imagenes.slice(0, 4);
  }

  get albumRestantes(): number {
    return Math.max(0, this.imagenes.length - 4);
  }

  get albumClase(): string {
    return 'album-' + Math.min(this.imagenes.length, 4);
  }

  get linkComentario(): string | null {
    if (!this.publicacion) return null;
    const canal = this.publicacion.telegram_chat_id;
    const msg = this.publicacion.telegram_message_id;
    if (!canal || !msg) return null;
    const id = canal.startsWith('-100') ? canal.slice(4) : canal.replace(/^-/, '');
    return `https://t.me/c/${id}/${msg}`;
  }

  abrirComentario(): void {
    const l = this.linkComentario;
    if (l) window.open(l, '_blank');
  }

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
