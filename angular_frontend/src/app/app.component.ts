import { Component, OnInit } from '@angular/core';
import { takeUntil } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { ConfiguracionService } from './services/configuracion.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  title = 'CanalVideos';

  private killed = new Subject<void>();

  constructor(private config: ConfiguracionService) {}

  ngOnInit(): void {
    // Primero aplica el tema según el modo persistido en backend (sin depender
    // de que el toggle o el home se monten antes), y en segundo plano carga la
    // config para que el toggle se ponga al día en cuanto llegue.
    this.config.cargar();
    this.config.config$.pipe(takeUntil(this.killed)).subscribe((c) => {
      this.aplicarTema(c?.modo === 'test');
    });
  }

  private aplicarTema(esTest: boolean): void {
    document.body.classList.toggle('modo-test', esTest);
  }
}