import { Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { TitulosService } from '../../services/titulos.service';
import { PublicacionesService } from '../../services/publicaciones.service';
import { TmdbService } from '../../services/tmdb.service';
import { ApiService } from '../../services/api.service';
import {
  Publicacion,
  SugerenciaHit,
  Titulo,
  TmdbHit,
  etiquetaTipo,
  nombreMostrable,
  urlPoster
} from '../../models/modelos';

interface FilaTitulo extends Titulo {
  estado: 'borrador' | 'publicado' | 'sin';
  publicacionId?: number;
}

@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css']
})
export class HomeComponent implements OnInit, OnDestroy {
  cargando = true;
  errorBackend: string | null = null;

  titulos: FilaTitulo[] = [];
  porTitulo: Record<number, Publicacion | undefined> = {};
  borradores = 0;
  publicados = 0;

  busqueda = '';
  buscando = false;
  buscado = false;
  resultados: TmdbHit[] = [];
  errorBusqueda: string | null = null;
  sugerencia: SugerenciaHit | null = null;
  guardandoTmdb: Record<number, boolean> = {};

  panel: FilaTitulo | null = null;

  private killed = new Subject<void>();

  constructor(
    private titulosService: TitulosService,
    private publicacionesService: PublicacionesService,
    private tmdbService: TmdbService,
    private api: ApiService,
    private router: Router,
    private snack: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.cargar();
  }

  ngOnDestroy(): void {
    this.killed.next();
    this.killed.complete();
  }

  cargar(): void {
    this.cargando = true;
    this.errorBackend = null;
    this.titulosService.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (lista) => {
        this.publicacionesService.listar().pipe(takeUntil(this.killed)).subscribe({
          next: (pubs) => {
            const estadoPor: Record<number, Publicacion | undefined> = {};
            pubs.forEach((p) => {
              estadoPor[p.titulo_id] = p;
            });
            this.porTitulo = estadoPor;
            this.titulos = lista.map((t) => {
              const pub = estadoPor[t.id];
              return {
                ...t,
                estado: pub ? pub.estado : 'sin',
                publicacionId: pub ? pub.id : undefined
              };
            });
            this.borradores = this.titulos.filter((t) => t.estado === 'borrador').length;
            this.publicados = this.titulos.filter((t) => t.estado === 'publicado').length;
            this.panel = this.elegirPanel();
            this.cargando = false;
          },
          error: (e: HttpErrorResponse) => this.fallarCarga(e)
        });
      },
      error: (e: HttpErrorResponse) => this.fallarCarga(e)
    });
  }

  private fallarCarga(e: HttpErrorResponse): void {
    this.errorBackend = this.api.mensajeDeError(e);
    this.cargando = false;
  }

  private elegirPanel(): FilaTitulo | null {
    if (!this.titulos.length) return null;
    return this.titulos.find((t) => t.estado === 'borrador')
      ?? this.titulos[this.titulos.length - 1];
  }

  nombre(t: Titulo): string {
    return nombreMostrable(t);
  }

  nombreTitulo(s: string): string {
    const c = s.trim().charAt(0);
    return c ? c.toUpperCase() : '?';
  }

  poster(t: { poster_path: string | null }): string | null {
    return urlPoster(t.poster_path);
  }

  posterUrl(p: string | null): string | null {
    return urlPoster(p);
  }

  tipo(t: TmdbHit['media_type'] | Titulo['tipo']): string {
    return etiquetaTipo(t);
  }

  captionPanel(t: Titulo): string | null {
    const p = this.porTitulo[t.id];
    return p ? p.caption : null;
  }

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

  /** Re-ejecuta la búsqueda con el título corregido sugerido. */
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

  abrir(t: FilaTitulo): void {
    this.router.navigate(['/publicacion', t.id]);
  }
}