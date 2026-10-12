import { Component, EventEmitter, OnDestroy, OnInit, Output } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { ConfiguracionService, ModoTelegram } from '../../services/configuracion.service';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-modo-toggle',
  templateUrl: './modo-toggle.component.html',
  styleUrls: ['./modo-toggle.component.css']
})
export class ModoToggleComponent implements OnInit, OnDestroy {
  modo: ModoTelegram = 'real';
  testConfigurado = false;
  cambiando = false;

  /** Se dispara cuando el modo cambió en el backend (para que el padre recargue). */
  @Output() modoCambiado = new EventEmitter<ModoTelegram>();

  private killed = new Subject<void>();

  constructor(
    private config: ConfiguracionService,
    private api: ApiService,
    private snack: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.config.config$.pipe(takeUntil(this.killed)).subscribe((c) => {
      if (c) {
        this.modo = c.modo;
        this.testConfigurado = c.test_configurado;
      }
    });
  }

  ngOnDestroy(): void {
    this.killed.next();
    this.killed.complete();
  }

  elegir(nuevo: ModoTelegram): void {
    if (nuevo === this.modo || this.cambiando) return;
    if (nuevo === 'test' && !this.testConfigurado) {
      this.snack.open('Primero configurá el canal de pruebas en Configuración', 'OK', { duration: 6000 });
      return;
    }
    this.cambiando = true;
    this.config.cambiarModo(nuevo).pipe(takeUntil(this.killed)).subscribe({
      next: (c) => {
        this.config.aplicar(c);
        this.cambiando = false;
        this.snack.open(nuevo === 'test' ? 'MODO PRUEBA activado' : 'MODO REAL activado', '✓', { duration: 2200 });
        this.modoCambiado.emit(c.modo);
      },
      error: (e: HttpErrorResponse) => {
        this.cambiando = false;
        this.snack.open(this.api.mensajeDeError(e), 'OK', { duration: 5000 });
      }
    });
  }
}