import { Component, ElementRef, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  BootstrapAdmin,
  BootstrapPayload,
  CrearBaseResultado,
  DatosConexion,
  DatosInventario,
  DatosService,
  EstadoRespaldos,
  PruebaAdmin,
  PruebaConexion,
  Respaldo,
  RespaldoConfig,
  RevisionIntegridad
} from '../../services/datos.service';
import { ApiService } from '../../services/api.service';
import { FsItem, FsService } from '../../services/fs.service';

type AccionConfirmar = 'restaurar' | 'eliminar' | 'importar';

interface Confirmacion {
  accion: AccionConfirmar;
  id?: number;
  archivo: string;
  esperado: string;
  titulo: string;
  aviso: string;
  detalle?: string;
  boton: string;
}

/** Campo de carpeta que se puede elegir con el explorador. */
type DestinoRuta = 'dir_auto' | 'dir_manual' | 'bin_dir';

interface Picker {
  abierto: boolean;
  destino: DestinoRuta | null;
  ruta: string;
  padre: string | null;
  carpetas: FsItem[];
  sitios: FsItem[];
  unidades: FsItem[];
  cargando: boolean;
  error: string;
}

const PICKER_CERRADO: Picker = {
  abierto: false,
  destino: null,
  ruta: '',
  padre: null,
  carpetas: [],
  sitios: [],
  unidades: [],
  cargando: false,
  error: ''
};

@Component({
  selector: 'app-datos',
  templateUrl: './datos.component.html',
  styleUrls: ['./datos.component.css']
})
export class DatosComponent implements OnInit, OnDestroy {
  @ViewChild('archivoInput') archivoInput?: ElementRef<HTMLInputElement>;

  cargando = true;
  error = '';
  conexion: DatosConexion | null = null;
  inventario: DatosInventario | null = null;
  cfg: RespaldoConfig = {
    auto_activo: true,
    modo: 'intervalo',
    intervalo_horas: 24,
    hora_diaria: '03:00',
    dir_auto: '',
    dir_manual: '',
    bin_dir: '',
    retencion: 10,
    bin_dump_disponible: false,
    bin_cliente_disponible: false,
    nombre_base_datos: ''
  };
  estado: EstadoRespaldos | null = null;
  lista: Respaldo[] = [];
  revision: RevisionIntegridad | null = null;
  private ocupadoPrev = false;

  prueba: PruebaConexion | null = null;
  probando = false;
  guardando = false;
  lanzando = false;

  confirma: Confirmacion | null = null;
  confirmaTxt = '';
  confirmaError = '';

  archivoImportar: File | null = null;

  // ---- explorador de carpetas (botón "…") ----
  picker: Picker = { ...PICKER_CERRADO };

  // ---- asistente de base de datos (primer arranque) ----
  bdDisponible = true;
  boot: BootstrapAdmin | null = null;
  mostrarAsistente = false;
  wiz: BootstrapPayload = {
    host: 'localhost',
    puerto: '3306',
    nombre_base: '',
    usuario_admin: '',
    password_admin: '',
    usuario_app: '',
    password_app: ''
  };
  pruebaAdmin: PruebaAdmin | null = null;
  probandoAdmin = false;
  creandoBase = false;
  creacionLista: CrearBaseResultado | null = null;
  creacionModo: 'base' | 'tablas' | null = null;

  private timer?: number;

  constructor(
    private datos: DatosService,
    private api: ApiService,
    private fs: FsService,
    private snack: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.cargar();
    this.timer = window.setInterval(() => this.refrescarLista(), 2000);
  }

  ngOnDestroy(): void {
    if (this.timer) window.clearInterval(this.timer);
  }

  // ---- carga ----

  cargar(): void {
    this.cargando = true;
    this.error = '';
    this.datos.estado().subscribe({
      next: (e) => {
        this.bdDisponible = e.bd_disponible !== false;
        this.conexion = e.conexion;
        this.inventario = e.inventario;
        this.cfg = e.config;
        this.estado = e.estado;
        this.boot = e.bootstrap || null;
        this.initWiz(e.bootstrap);
        this.cargando = false;
        this.refrescarLista();
      },
      error: (err) => {
        // La base puede no existir todavía: mostrar el asistente igualmente.
        this.cargando = false;
        this.bdDisponible = false;
        this.mostrarAsistente = true;
        this.error = '';
        this.datos.bootstrap().subscribe({
          next: (b) => { this.boot = b.config; this.initWiz(b.config); },
          error: () => { this.error = this.api.mensajeDeError(err); }
        });
      }
    });
  }

  /** Rellena el asistente con los valores guardados (sin contraseñas). */
  private initWiz(b: BootstrapAdmin | null): void {
    if (!b) return;
    this.wiz = {
      host: b.host || 'localhost',
      puerto: b.puerto || '3306',
      nombre_base: b.nombre_base || '',
      usuario_admin: b.usuario_admin || '',
      password_admin: '',
      usuario_app: b.usuario_app || '',
      password_app: ''
    };
  }

  abrirAsistente(): void {
    this.mostrarAsistente = !this.mostrarAsistente;
  }

  /** Escenario A: las credenciales pueden crear bases de datos (root/admin). */
  get escA(): boolean {
    return this.pruebaAdmin?.ok === true && this.pruebaAdmin.puedeCrearBase === true;
  }

  /** Escenario B: usuario restringido (sin permiso para crear bases, quizá con permisos sobre la base). */
  get escB(): boolean {
    return this.pruebaAdmin?.ok === true && this.pruebaAdmin.puedeCrearBase === false;
  }

  get baseExiste(): boolean {
    return this.pruebaAdmin?.existeBase === true;
  }

  get puedeTablas(): boolean {
    return this.pruebaAdmin?.ok === true && this.pruebaAdmin.puedeCrearTablas === true;
  }

  /** El usuario de la app ya existe en el servidor (se reutilizará sin cambiarle la contraseña). */
  get appUserExiste(): boolean {
    return this.pruebaAdmin?.ok === true && this.pruebaAdmin.usuarioAppExiste === true;
  }

  /** SQL que el usuario debe ejecutar él mismo en el Escenario B (usuario dedicado). */
  get sqlConfigBd(): string {
    const base = this.wiz.nombre_base || 'mi_base';
    const usuario = this.wiz.usuario_app || this.wiz.usuario_admin || 'mi_usuario';
    const clave = this.wiz.password_app || '<tu-clave>';
    return `CREATE DATABASE \`${base}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n`
      + `CREATE USER '${usuario}'@'localhost' IDENTIFIED BY '${clave}';\n`
      + `GRANT ALL PRIVILEGES ON \`${base}\`.* TO '${usuario}'@'localhost';\n`
      + `FLUSH PRIVILEGES;`;
  }

  probarAdmin(): void {
    this.probandoAdmin = true;
    this.pruebaAdmin = null;
    this.creacionLista = null;
    this.creacionModo = null;
    this.datos.probarAdmin(this.wiz).subscribe({
      next: (p) => { this.probandoAdmin = false; this.pruebaAdmin = p; },
      error: (err) => { this.probandoAdmin = false; this.avisar(this.api.mensajeDeError(err), false); }
    });
  }

  crearBase(): void {
    this.creandoBase = true;
    this.creacionLista = null;
    this.datos.crearBase(this.wiz).subscribe({
      next: (r) => {
        this.creandoBase = false;
        this.creacionLista = r;
        this.creacionModo = 'base';
        this.wiz.password_admin = '';
        this.wiz.password_app = '';
        this.avisar('Base de datos creada: ' + r.base + '. Reinicia la aplicación.', true);
      },
      error: (err) => {
        this.creandoBase = false;
        this.avisar(this.api.mensajeDeError(err), false);
      }
    });
  }

  /** Escenario B: la base ya existe; solo se crean las tablas que falten. */
  crearTablas(): void {
    this.creandoBase = true;
    this.creacionLista = null;
    this.datos.crearTablas(this.wiz).subscribe({
      next: (r) => {
        this.creandoBase = false;
        this.creacionLista = r;
        this.creacionModo = 'tablas';
        this.wiz.password_admin = '';
        this.wiz.password_app = '';
        this.avisar('Tablas creadas en ' + r.base + '. Reinicia la aplicación.', true);
      },
      error: (err) => {
        this.creandoBase = false;
        this.avisar(this.api.mensajeDeError(err), false);
      }
    });
  }

  copiarSql(): void {
    const texto = this.sqlConfigBd;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(texto).then(
        () => this.avisar('SQL copiado al portapapeles', true),
        () => this.avisar('No se pudo copiar; selecciona el texto manualmente.', false)
      );
    } else {
      this.avisar('Selecciona y copia el texto manualmente.', false);
    }
  }

  /** Silencioso: refresca historial y estado sin parpadeos. */
  refrescarLista(): void {
    this.datos.listar().subscribe({
      next: (r) => {
        const ocupadoAntes = this.ocupadoPrev;
        this.lista = r.respaldos;
        this.estado = r.estado;
        const ocupadoAhora = !!r.estado?.ocupado;
        this.ocupadoPrev = ocupadoAhora;
        if (ocupadoAntes && !ocupadoAhora) this.avisarFin();
      },
      error: () => {
        /* el backend puede estar ocupado; se reintenta en la siguiente pasada */
      }
    });
  }

  /** Al terminar una restauración/importación avisa que conviene recargar la app. */
  private avisarFin(): void {
    const ult = this.lista[0];
    if (!ult) return;
    const peligrosa = ult.motivo === 'restauracion' || ult.motivo === 'importacion';
    if (peligrosa && ult.estado === 'completado') {
      this.avisar('Datos restaurados. Se recomienda recargar la app (F5) para ver el contenido restaurado.', true);
    } else if (peligrosa && ult.estado === 'fallo') {
      this.avisar('La restauración falló. Revisa el detalle en el historial; tus datos no se perdieron '
        + '(hay un respaldo de seguridad).', false);
    }
  }

  // ---- conexión ----

  probarConexion(): void {
    this.probando = true;
    this.datos.probar().subscribe({
      next: (p) => {
        this.probando = false;
        this.prueba = p;
        this.avisar(p.ok ? 'Conexión correcta' : p.mensaje, p.ok);
      },
      error: (err) => {
        this.probando = false;
        this.avisar(this.api.mensajeDeError(err), false);
      }
    });
  }

  // ---- configuración ----

  guardarConfig(): void {
    this.guardando = true;
    const payload = {
      auto_activo: this.cfg.auto_activo,
      modo: this.cfg.modo,
      intervalo_horas: Number(this.cfg.intervalo_horas),
      hora_diaria: this.cfg.hora_diaria,
      dir_auto: this.cfg.dir_auto,
      dir_manual: this.cfg.dir_manual,
      bin_dir: this.cfg.bin_dir,
      retencion: Number(this.cfg.retencion)
    };
    this.datos.guardarConfig(payload).subscribe({
      next: (c) => {
        this.guardando = false;
        this.cfg = c;
        if (c.inventario) this.inventario = c.inventario;
        this.avisar('Configuración de respaldos guardada', true);
      },
      error: (err) => {
        this.guardando = false;
        this.avisar(this.api.mensajeDeError(err), false);
      }
    });
  }

  // ---- explorador de carpetas ----

  /** Abre el explorador para elegir una carpeta y asignarla al campo indicado. */
  elegirCarpeta(destino: DestinoRuta): void {
    this.picker = { ...PICKER_CERRADO, abierto: true, destino, cargando: true, ruta: (this.cfg[destino] || '').trim() };
    this.abrirRuta(this.picker.ruta);
  }

  abrirRuta(ruta: string): void {
    this.picker.cargando = true;
    this.picker.error = '';
    this.fs.listar(ruta).subscribe({
      next: (l) => {
        this.picker.cargando = false;
        this.picker.ruta = l.ruta;
        this.picker.padre = l.padre;
        this.picker.carpetas = l.carpetas;
        this.picker.sitios = l.sitios;
        this.picker.unidades = l.unidades;
      },
      error: (err) => {
        this.picker.cargando = false;
        this.picker.error = this.api.mensajeDeError(err);
      }
    });
  }

  subirCarpeta(): void {
    if (this.picker.padre) this.abrirRuta(this.picker.padre);
  }

  /** Confirma la carpeta que está abierta en el explorador. */
  usarCarpeta(): void {
    if (!this.picker.destino) return;
    this.cfg[this.picker.destino] = this.picker.ruta;
    this.cerrarPicker();
  }

  cerrarPicker(): void {
    this.picker = { ...PICKER_CERRADO };
  }

  // ---- operaciones ----

  respaldarAhora(): void {
    if (this.estado?.ocupado) return;
    this.lanzando = true;
    this.datos.crear('manual').subscribe({
      next: () => {
        this.lanzando = false;
        this.avisar('Respaldo manual iniciado. Puede tardar según el tamaño de la base.', true);
        this.refrescarLista();
      },
      error: (err) => {
        this.lanzando = false;
        this.avisar(this.api.mensajeDeError(err), false);
      }
    });
  }

  verificar(r: Respaldo): void {
    this.datos.verificar(r.id).subscribe({
      next: (v) => {
        r.mensaje = v.mensaje;
        r.verificado_en = new Date().toISOString();
        this.avisar(v.ok ? 'Verificación correcta: el archivo está intacto.' : v.mensaje, v.ok);
      },
      error: (err) => this.avisar(this.api.mensajeDeError(err), false)
    });
  }

  revisarIntegridad(): void {
    this.datos.revisarIntegridad().subscribe({
      next: (rev) => {
        this.revision = rev;
        this.refrescarLista();
        const total = rev.interrumpidos + rev.archivos_parciales_borrados + rev.respaldos_sin_archivo;
        this.avisar(total === 0
          ? 'Todo en orden: no se encontraron problemas.'
          : `Revisión: ${rev.interrumpidos} interrumpido(s), ${rev.archivos_parciales_borrados} archivo(s) a medias descartado(s), `
            + `${rev.respaldos_sin_archivo} respaldo(s) sin archivo.`, total === 0);
      },
      error: (err) => this.avisar(this.api.mensajeDeError(err), false)
    });
  }

  // ---- confirmación (operaciones destructivas) ----

  pedirRestaurar(r: Respaldo): void {
    const base = this.cfg.nombre_base_datos || '';
    this.confirma = {
      accion: 'restaurar',
      id: r.id,
      archivo: r.archivo,
      esperado: base,
      titulo: 'Restaurar este respaldo',
      aviso: 'Esto REEMPLAZA los datos actuales de la base de datos por los del respaldo. También vuelven a como '
        + 'estaban en ese momento los bots, los destinos, la cola de publicaciones y los ajustes. '
        + 'Antes de continuar se hará automáticamente un respaldo de seguridad de lo que hay ahora '
        + '(aparecerá en el historial).',
      detalle: 'Base de datos que se reemplazará: ' + (base || '(sin nombre)'),
      boton: 'RESTAURAR'
    };
    this.confirmaTxt = '';
    this.confirmaError = '';
  }

  pedirEliminar(r: Respaldo): void {
    this.confirma = {
      accion: 'eliminar',
      id: r.id,
      archivo: r.archivo,
      esperado: r.archivo,
      titulo: 'Eliminar este respaldo',
      aviso: 'Se borrará el archivo del disco y su registro. Esta acción no se puede deshacer. '
        + 'Si es tu única copia de seguridad, no podrás recuperarla.',
      detalle: 'Carpeta: ' + (r.ruta || '(no encontrada)'),
      boton: 'ELIMINAR'
    };
    this.confirmaTxt = '';
    this.confirmaError = '';
  }

  pedirImportar(): void {
    this.archivoInput?.nativeElement.click();
  }

  onArchivo(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const f = input.files && input.files.length ? input.files[0] : null;
    input.value = '';
    if (!f) return;
    const bajo = f.name.toLowerCase();
    if (!bajo.endsWith('.sql') && !bajo.endsWith('.gz')) {
      this.avisar('El archivo debe ser .sql o .sql.gz', false);
      return;
    }
    this.archivoImportar = f;
    const base = this.cfg.nombre_base_datos || '';
    this.confirma = {
      accion: 'importar',
      archivo: f.name,
      esperado: base,
      titulo: 'Importar y restaurar',
      aviso: 'Se restaurará el contenido de este archivo sobre la base actual (REEMPLAZA los datos de ahora). '
        + 'También vuelven a como estaban en ese momento los bots, los destinos, la cola de publicaciones '
        + 'y los ajustes. Antes de continuar se hará automáticamente un respaldo de seguridad.',
      detalle: 'Base de datos que se reemplazará: ' + (base || '(sin nombre)'),
      boton: 'IMPORTAR Y RESTAURAR'
    };
    this.confirmaTxt = '';
    this.confirmaError = '';
  }

  confirmar(): void {
    if (!this.confirma) return;
    const c = this.confirma;
    const esperado = c.esperado || '';
    const escrito = (this.confirmaTxt || '').trim();
    const coincide = c.accion === 'eliminar'
      ? escrito === esperado
      : escrito.toLowerCase() === esperado.toLowerCase();
    if (!coincide) {
      this.confirmaError = `Escribe exactamente: ${esperado}`;
      return;
    }
    const cerrar = () => { this.confirma = null; this.confirmaTxt = ''; this.confirmaError = ''; };
    if (c.accion === 'restaurar' && c.id != null) {
      this.datos.restaurar(c.id, escrito).subscribe({
        next: () => { cerrar(); this.avisar('Restauración iniciada. No cierres la app hasta que termine.', true); this.refrescarLista(); },
        error: (err) => { this.confirmaError = this.api.mensajeDeError(err); }
      });
    } else if (c.accion === 'eliminar' && c.id != null) {
      this.datos.eliminar(c.id, escrito).subscribe({
        next: () => { cerrar(); this.avisar('Respaldo eliminado', true); this.refrescarLista(); },
        error: (err) => { this.confirmaError = this.api.mensajeDeError(err); }
      });
    } else if (c.accion === 'importar' && this.archivoImportar) {
      const file = this.archivoImportar;
      this.datos.importar(file, escrito).subscribe({
        next: () => { cerrar(); this.archivoImportar = null; this.avisar('Importación iniciada. No cierres la app hasta que termine.', true); this.refrescarLista(); },
        error: (err) => { this.confirmaError = this.api.mensajeDeError(err); }
      });
    }
  }

  cancelarConfirmacion(): void {
    this.confirma = null;
    this.confirmaTxt = '';
    this.confirmaError = '';
    this.archivoImportar = null;
  }

  // ---- presentación ----

  trackId(_i: number, r: Respaldo): number {
    return r.id;
  }

  urlDescarga(id: number): string {
    return this.datos.urlDescarga(id);
  }

  tamano(bytes: number | null): string {
    if (bytes == null) return '—';
    const u = ['B', 'KB', 'MB', 'GB'];
    let v = bytes;
    let i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return `${v.toLocaleString('es', { maximumFractionDigits: v < 10 && i > 0 ? 1 : 0 })} ${u[i]}`;
  }

  duracion(ms: number | null): string {
    if (ms == null) return '—';
    if (ms < 1000) return `${ms} ms`;
    const s = ms / 1000;
    if (s < 60) return `${s.toLocaleString('es', { maximumFractionDigits: 1 })} s`;
    const m = Math.floor(s / 60);
    return `${m} min ${Math.round(s % 60)} s`;
  }

  etiquetaEstado(estado: string): string {
    switch (estado) {
      case 'en_progreso': return 'EN CURSO';
      case 'completado': return 'COMPLETADO';
      case 'fallo': return 'FALLÓ';
      case 'interrumpido': return 'INTERRUMPIDO';
      default: return estado.toUpperCase();
    }
  }

  claseEstado(estado: string): string {
    switch (estado) {
      case 'completado': return 'ok';
      case 'fallo': return 'fallo';
      case 'interrumpido': return 'aviso';
      default: return 'curso';
    }
  }

  etiquetaFila(r: Respaldo): string {
    switch (r.motivo) {
      case 'restauracion': return 'Restauración';
      case 'importacion': return 'Importación';
      case 'programado': return 'Automático';
      case 'pre_restauracion':
      case 'pre_importacion': return 'Respaldo de seguridad';
      default: return r.tipo === 'automatico' ? 'Automático' : 'Manual';
    }
  }

  checksumCorto(c: string | null): string {
    if (!c) return '—';
    return c.slice(0, 10) + '…' + c.slice(-4);
  }

  get operacionActiva(): string {
    const op = this.estado?.operacion;
    if (!op) return '';
    switch (op) {
      case 'respaldo_automatico': return 'Creando respaldo automático';
      case 'respaldo_manual': return 'Creando respaldo manual';
      case 'restauracion': return 'Restaurando un respaldo';
      case 'importacion': return 'Importando y restaurando';
      default: return 'Operación en curso';
    }
  }

  private avisar(mensaje: string, ok: boolean): void {
    this.snack.open(mensaje, 'OK', { duration: ok ? 4000 : 7000 });
  }
}
