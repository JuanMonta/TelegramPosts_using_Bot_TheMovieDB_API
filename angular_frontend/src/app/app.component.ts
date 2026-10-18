import { Component, OnDestroy, OnInit } from '@angular/core';
import { takeUntil } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ConfiguracionService } from './services/configuracion.service';
import { DatosService, Respaldo } from './services/datos.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  title = 'CanalVideos';

  private killed = new Subject<void>();
  private respaldoTimer?: number;
  private sembrado = false;
  private ultimoEventoId = 0;

  constructor(
    private config: ConfiguracionService,
    private datos: DatosService,
    private snack: MatSnackBar
  ) {}

  ngOnInit(): void {
    // Primero aplica el tema según el modo persistido en backend (sin depender
    // de que el toggle o el home se monten antes), y en segundo plano carga la
    // config para que el toggle se ponga al día en cuanto llegue.
    this.config.cargar();
    this.config.config$.pipe(takeUntil(this.killed)).subscribe((c) => {
      this.aplicarTema(c?.modo === 'test');
    });

    // Avisos in-app de respaldos: aunque la ventana de configuración esté
    // cerrada, aquí (componente raíz, siempre vivo) se detecta cuándo termina
    // un respaldo o una restauración y se muestra un aviso.
    this.vigilarRespaldos();
    this.respaldoTimer = window.setInterval(() => this.vigilarRespaldos(), 15000);
  }

  ngOnDestroy(): void {
    this.killed.next();
    this.killed.complete();
    if (this.respaldoTimer) window.clearInterval(this.respaldoTimer);
  }

  private aplicarTema(esTest: boolean): void {
    document.body.classList.toggle('modo-test', esTest);
  }

  private vigilarRespaldos(): void {
    this.datos.eventos(this.sembrado ? this.ultimoEventoId : null).subscribe({
      next: (r) => {
        const eventos = r.eventos || [];
        if (!this.sembrado) {
          // Primera pasada: solo memoriza el punto de partida, sin avisar de lo viejo.
          this.sembrado = true;
          this.ultimoEventoId = eventos.reduce((max, e) => Math.max(max, e.id), 0);
          return;
        }
        if (!eventos.length) return;
        eventos.forEach((e) => this.avisarRespaldo(e));
        this.ultimoEventoId = eventos.reduce((max, e) => Math.max(max, e.id), this.ultimoEventoId);
      },
      error: () => {
        // El backend puede no estar arriba todavía; se reintenta en la siguiente pasada.
      }
    });
  }

  private avisarRespaldo(e: Respaldo): void {
    let sujeto: string;
    switch (e.motivo) {
      case 'restauracion': sujeto = 'La restauración'; break;
      case 'importacion': sujeto = 'La importación'; break;
      case 'pre_restauracion':
      case 'pre_importacion': sujeto = 'El respaldo de seguridad'; break;
      default: sujeto = e.tipo === 'automatico' ? 'El respaldo automático' : 'El respaldo'; break;
    }

    let mensaje: string;
    let duracion = 5000;
    if (e.estado === 'completado') {
      mensaje = `${sujeto} se completó (${e.archivo})`;
    } else if (e.estado === 'interrumpido') {
      mensaje = `${sujeto} quedó interrumpido por un cierre inesperado. Revisa la pestaña Datos.`;
      duracion = 9000;
    } else {
      mensaje = `${sujeto} falló: ${e.mensaje || 'sin detalle'}`;
      duracion = 9000;
    }
    this.snack.open(mensaje, 'OK', { duration: duracion });
  }
}
