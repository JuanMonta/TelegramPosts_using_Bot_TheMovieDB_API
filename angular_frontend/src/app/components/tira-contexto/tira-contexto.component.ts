import { Component, ElementRef, EventEmitter, OnDestroy, OnInit, Output, ViewChild } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { Acoplamiento, destinosDeModo, etiquetaModo } from '../../models/modelos';
import { AcoplamientoService } from '../../services/acoplamiento.service';
import { CanalService } from '../../services/canal.service';
import { ConfiguracionService, ModoTelegram } from '../../services/configuracion.service';
import { ConjuntoTrabajoService } from '../../services/conjunto-trabajo.service';

/**
 * Tira de contexto de trabajo: modo + conjunto activo + sus destinos.
 * Vive en el header de Home y en la barra del editor con el mismo estado.
 * Los destinos se muestran divididos por modo: solo cuentan los del modo activo.
 */
@Component({
  selector: 'app-tira-contexto',
  templateUrl: './tira-contexto.component.html',
  styleUrls: ['./tira-contexto.component.css']
})
export class TiraContextoComponent implements OnInit, OnDestroy {
  @Output() modoCambiado = new EventEmitter<ModoTelegram>();
  @Output() crearConjunto = new EventEmitter<void>();
  @ViewChild('selector') selector!: ElementRef<HTMLSelectElement>;

  acoplamientos: Acoplamiento[] = [];
  seleccionado: number | null = null;
  modo: ModoTelegram | null = null;

  private killed = new Subject<void>();

  constructor(
    private acoplamientosSvc: AcoplamientoService,
    private canalSvc: CanalService,
    private trabajo: ConjuntoTrabajoService,
    private config: ConfiguracionService
  ) {}

  ngOnInit(): void {
    this.trabajo.id$.pipe(takeUntil(this.killed)).subscribe((id) => (this.seleccionado = id));
    this.config.config$.pipe(takeUntil(this.killed)).subscribe((c) => (this.modo = c?.modo ?? null));
    this.config.cargar();
    this.cargarAcoplamientos(true);
    this.acoplamientosSvc.cambios$.pipe(takeUntil(this.killed))
      .subscribe(() => this.cargarAcoplamientos(false));
    this.canalSvc.cambios$.pipe(takeUntil(this.killed))
      .subscribe(() => this.cargarAcoplamientos(false));
  }

  private cargarAcoplamientos(restaurar: boolean): void {
    this.acoplamientosSvc.listar().pipe(takeUntil(this.killed)).subscribe({
      next: (a) => {
        this.acoplamientos = a;
        if (restaurar) this.trabajo.restaurar(a);
        else this.trabajo.limpiarSiFalta(a);
      },
      error: () => undefined
    });
  }

  ngOnDestroy(): void {
    this.killed.next();
    this.killed.complete();
  }

  trackByAcoplamientoId(_i: number, a: Acoplamiento): number {
    return a.id;
  }

  elegir(id: number | null): void {
    this.trabajo.elegir(id);
  }

  enfocarSelector(): void {
    try {
      this.selector?.nativeElement.focus();
    } catch {
      // sin foco disponible: no-op
    }
  }

  etiquetaModoActual(): string {
    return etiquetaModo(this.modo);
  }

  /** Destinos del conjunto que sirven en el modo activo (divididos real/prueba). */
  utiles(a: Acoplamiento): number {
    return destinosDeModo(a.canales, this.modo).length;
  }

  /** Resumen del conjunto elegido: destinos del modo activo o aviso de vacío. */
  get resumen(): string {
    const ent = this.acoplamientos.find((a) => a.id === this.seleccionado);
    if (!ent) return '';
    const utiles = destinosDeModo(ent.canales, this.modo);
    if (!utiles.length) return this.modo ? `Vacío en ${etiquetaModo(this.modo)}` : 'Vacío (0 destinos)';
    const nombres = utiles.map((c) => c.nombre);
    const base = nombres.slice(0, 3).join(' · ');
    return nombres.length > 3 ? `${base} · +${nombres.length - 3}` : base;
  }

  get resumenVacio(): boolean {
    const ent = this.acoplamientos.find((a) => a.id === this.seleccionado);
    return !!ent && !destinosDeModo(ent.canales, this.modo).length;
  }
}
