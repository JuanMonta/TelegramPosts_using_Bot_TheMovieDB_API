import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { environment } from '../../environments/environment';

/** Datos de conexión vigentes (sin contraseña). */
export interface DatosConexion {
  url: string;
  motor: string;
  host: string;
  puerto: string;
  base: string;
  usuario: string;
  driver: string;
}

/** Conexión + resolución de rutas y binarios. */
export interface DatosInventario extends DatosConexion {
  raiz_app: string;
  bin_dump: string | null;
  bin_cliente: string | null;
  bin_dir: string;
  jdbc_disponible: boolean;
}

export interface PruebaConexion {
  ok: boolean;
  mensaje: string;
  version: string | null;
  base: string | null;
  tablas: number | null;
  tamanoMb: number | null;
  latenciaMs: number | null;
}

export interface RespaldoConfig {
  auto_activo: boolean;
  modo: 'intervalo' | 'diario';
  intervalo_horas: number;
  hora_diaria: string;
  dir_auto: string;
  dir_manual: string;
  bin_dir: string;
  retencion: number;
  proximo_automatico?: string | null;
  clave_invalida?: boolean;
  bin_dump_disponible: boolean;
  bin_cliente_disponible: boolean;
  nombre_base_datos: string;
}

export type RespaldoEstado = 'en_progreso' | 'completado' | 'fallo' | 'interrumpido';

export interface Respaldo {
  id: number;
  tipo: 'automatico' | 'manual';
  estado: RespaldoEstado;
  motivo: string;
  archivo: string;
  ruta: string;
  tamano_bytes: number | null;
  checksum_sha256: string | null;
  motor: string;
  version: string | null;
  mensaje: string | null;
  iniciado_en: string;
  terminado_en: string | null;
  duracion_ms: number | null;
  verificado_en: string | null;
  existe: boolean;
  operacion: boolean;
}

export interface EstadoRespaldos {
  ocupado: boolean;
  operacion: string | null;
  id_en_curso: number | null;
  proximo_automatico: string | null;
  ultimo_automatico?: Respaldo;
  ultimo_manual?: Respaldo;
}

export interface ListaRespaldos {
  respaldos: Respaldo[];
  estado: EstadoRespaldos;
}

export interface EventosRespaldos {
  eventos: Respaldo[];
  estado: EstadoRespaldos;
}

export interface DatosEstado {
  bd_disponible: boolean;
  conexion: DatosConexion;
  inventario: DatosInventario;
  config: RespaldoConfig;
  estado: EstadoRespaldos;
  bootstrap: BootstrapAdmin;
}

/** Campos del asistente de base de datos (config-db.yaml), sin contraseñas. */
export interface BootstrapAdmin {
  archivo: string;
  existe_archivo: boolean;
  enabled: boolean;
  host: string;
  puerto: string;
  nombre_base: string;
  usuario_admin: string;
  tiene_password_admin: boolean;
  usuario_app: string;
  tiene_password_app: boolean;
}

export interface PruebaAdmin {
  ok: boolean;
  mensaje: string;
  version: string | null;
  existeBase: boolean;
  tablas: number | null;
  puedeCrearBase: boolean | null;
  puedeCrearTablas: boolean | null;
  usuarioAppExiste: boolean | null;
}

export interface CrearBaseResultado {
  ok: boolean;
  base: string;
  tablas: number;
  host: string;
  puerto: string;
  usuario_app: string;
  mensaje: string;
}

/** Payload del asistente (los password vacíos conservan el valor guardado). */
export interface BootstrapPayload {
  host: string;
  puerto: string;
  nombre_base: string;
  usuario_admin: string;
  password_admin: string;
  usuario_app: string;
  password_app: string;
}

export interface RevisionIntegridad {
  interrumpidos: number;
  archivos_parciales_borrados: number;
  respaldos_sin_archivo: number;
}

export interface ResultadoVerificacion {
  id: number;
  archivo: string;
  ok: boolean;
  esperado?: string;
  calculado?: string;
  mensaje: string;
}

@Injectable({ providedIn: 'root' })
export class DatosService {
  constructor(private api: ApiService, private http: HttpClient) {}

  estado(): Observable<DatosEstado> {
    return this.api.get<DatosEstado>('/api/datos/estado');
  }

  probar(): Observable<PruebaConexion> {
    return this.api.post<PruebaConexion>('/api/datos/conexion/probar');
  }

  /** Asistente de base de datos: estado del archivo de conexión. */
  bootstrap(): Observable<{ bd_disponible: boolean; config: BootstrapAdmin }> {
    return this.api.get<{ bd_disponible: boolean; config: BootstrapAdmin }>('/api/datos/bootstrap');
  }

  /** Prueba la conexión de administrador (sin base seleccionada). */
  probarAdmin(p: BootstrapPayload): Observable<PruebaAdmin> {
    return this.api.post<PruebaAdmin>('/api/datos/bootstrap/probar-admin', p);
  }

  /** Crea la base de datos con el DDL canónico y el usuario de la app. */
  crearBase(p: BootstrapPayload): Observable<CrearBaseResultado> {
    return this.api.post<CrearBaseResultado>('/api/datos/bootstrap/crear', p);
  }

  /** Escenario B: la base ya existe; solo crea las tablas (no crea base ni usuario). */
  crearTablas(p: BootstrapPayload): Observable<CrearBaseResultado> {
    return this.api.post<CrearBaseResultado>('/api/datos/bootstrap/crear-tablas', p);
  }

  guardarConfig(cfg: Partial<RespaldoConfig>): Observable<RespaldoConfig & { inventario: DatosInventario }> {
    return this.api.put<RespaldoConfig & { inventario: DatosInventario }>('/api/datos/config', cfg);
  }

  listar(): Observable<ListaRespaldos> {
    return this.api.get<ListaRespaldos>('/api/datos/respaldos');
  }

  crear(tipo: 'manual' | 'automatico' = 'manual'): Observable<Respaldo> {
    return this.api.post<Respaldo>('/api/datos/respaldos', { tipo });
  }

  restaurar(id: number, confirmacion: string): Observable<Respaldo> {
    return this.api.post<Respaldo>(`/api/datos/respaldos/${id}/restaurar`, { confirmacion });
  }

  verificar(id: number): Observable<ResultadoVerificacion> {
    return this.api.post<ResultadoVerificacion>(`/api/datos/respaldos/${id}/verificar`);
  }

  eliminar(id: number, confirmacion: string): Observable<{ ok: boolean }> {
    return this.api.delete<{ ok: boolean }>(
      `/api/datos/respaldos/${id}?confirmacion=${encodeURIComponent(confirmacion)}`);
  }

  revisarIntegridad(): Observable<RevisionIntegridad> {
    return this.api.post<RevisionIntegridad>('/api/datos/integridad/revisar');
  }

  eventos(desdeId: number | null): Observable<EventosRespaldos> {
    const q = desdeId == null ? '' : `?desdeId=${desdeId}`;
    return this.api.get<EventosRespaldos>(`/api/datos/eventos${q}`);
  }

  /** Importa un .sql/.sql.gz y lo restaura (destructivo). */
  importar(archivo: File, confirmacion: string): Observable<Respaldo> {
    const form = new FormData();
    form.append('archivo', archivo, archivo.name);
    form.append('confirmacion', confirmacion);
    return this.http.post<Respaldo>(`${this.api.baseUrl}/api/datos/importar`, form);
  }

  /** URL directa de descarga (el navegador la usa como href). */
  urlDescarga(id: number): string {
    return `${environment.apiUrl}/api/datos/respaldos/${id}/descargar`;
  }
}
