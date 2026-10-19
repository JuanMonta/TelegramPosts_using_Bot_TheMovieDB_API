package api.crow.service;

import api.crow.domain.ConfigRespaldo;
import api.crow.domain.Respaldo;
import api.crow.repository.RespaldoRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Motor de respaldos e importación/exportación.
 *
 * - Ejecuta TODO en segundo plano (un solo hilo): no bloquea la UI y evita dos
 *   operaciones a la vez. La UI consulta el estado.
 * - Genera el volcado con mariadb-dump y lo comprime a .sql.gz. El archivo se
 *   escribe como *.part y solo al terminar (tamaño + SHA-256) se renombra: nunca
 *   queda un respaldo a medias con nombre definitivo.
 * - Cada ejecución nace 'en_progreso' en la tabla respaldos; si la app muere a
 *   mitad, al siguiente arranque {@link #revisarIntegridad()} la marca
 *   'interrumpido' (resiliencia ante apagones).
 * - Nunca ejecuta DROP DATABASE ni TRUNCATE. Restaurar/importar son destructivos
 *   y exigen confirmación + respaldo de seguridad previo obligatorio.
 */
@Slf4j
@Service
public class RespaldoService {

    public static final String OPER_RESPALDO_AUTO = "respaldo_automatico";
    public static final String OPER_RESPALDO_MANUAL = "respaldo_manual";
    public static final String OPER_RESTAURACION = "restauracion";
    public static final String OPER_IMPORTACION = "importacion";
    public static final String OPER_VERIFICACION = "verificacion";

    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final long TIMEOUT_DUMP_MIN = 30;
    private static final String VERSION = "1.0";

    private final RespaldoRepository repo;
    private final ConfigRespaldoService cfg;
    private final BaseDatosService db;
    private final Environment env;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "respaldo-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean ocupado = new AtomicBoolean(false);
    private final AtomicReference<String> operacion = new AtomicReference<>(null);
    private final AtomicLong enCursoId = new AtomicLong(0);

    public RespaldoService(RespaldoRepository repo, ConfigRespaldoService cfg, BaseDatosService db, Environment env) {
        this.repo = repo;
        this.cfg = cfg;
        this.db = db;
        this.env = env;
    }

    @PreDestroy
    void cerrar() {
        worker.shutdownNow();
    }

    // ============================================================
    //  Estado
    // ============================================================

    public boolean ocupado() {
        return ocupado.get();
    }

    /**
     * Lanza {@link IllegalStateException} si hay una operación de respaldo/restauración
     * en curso. Se usa para bloquear acciones que no deben convivir con ella
     * (revisar integridad, importar, cambiar la programación, etc.).
     */
    public void exigirLibre(String accion) {
        if (ocupado()) {
            throw new IllegalStateException("Hay una operación en curso (" + operacion.get()
                    + "). Espera a que termine para " + accion + ".");
        }
    }

    /**
     * Toma el bloqueo global para una operación externa (p. ej. la migración de motor),
     * de modo que no se solape con respaldos/restauraciones y la UI lo muestre como
     * "ocupado". Lanza si ya hay algo en curso. Quien lo toma DEBE llamar a {@link #liberar()}.
     */
    public void reservar(String operacionExterna) {
        if (!ocupado.compareAndSet(false, true)) {
            throw new IllegalStateException("Hay una operación en curso (" + operacion.get() + "). Espera a que termine.");
        }
        operacion.set(operacionExterna);
        enCursoId.set(0);
    }

    public Map<String, Object> estado() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ocupado", ocupado());
        out.put("operacion", operacion.get());
        out.put("id_en_curso", enCursoId.get() > 0 ? enCursoId.get() : null);
        out.put("proximo_automatico", proximoAutomatico());
        try {
            repo.findFirstByTipoOrderByIniciadoEnDesc(Respaldo.TIPO_AUTOMATICO)
                    .ifPresent(r -> out.put("ultimo_automatico", resumen(r)));
            repo.findAllByOrderByIniciadoEnDesc().stream()
                    .filter(r -> Respaldo.TIPO_MANUAL.equals(r.getTipo()))
                    .findFirst()
                    .ifPresent(r -> out.put("ultimo_manual", resumen(r)));
        } catch (Exception e) {
            // Tabla respaldos todavía no existe (base sin crear): estado mínimo.
        }
        return out;
    }

    /**
     * Estado mínimo SIN tocar la base de datos: para el primer arranque (base
     * todavía no creada), donde consultar el historial bloquearía la respuesta.
     */
    public Map<String, Object> estadoSinBd() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ocupado", ocupado());
        out.put("operacion", operacion.get());
        out.put("id_en_curso", enCursoId.get() > 0 ? enCursoId.get() : null);
        out.put("proximo_automatico", null);
        return out;
    }

    /** Próxima ejecución automática prevista (o null si está desactivado). */
    public LocalDateTime proximoAutomatico() {
        try {
            ConfigRespaldoService.Config c = cfg.cargar();
            if (!c.autoActivo()) return null;
            Optional<Respaldo> last = repo.findFirstByTipoOrderByIniciadoEnDesc(Respaldo.TIPO_AUTOMATICO);
            if (ConfigRespaldo.MODO_DIARIO.equals(c.modo())) {
                LocalTime hora = LocalTime.parse(c.horaDiaria());
                LocalDateTime hoy = LocalDate.now().atTime(hora);
                if (last.isPresent() && !last.get().getIniciadoEn().isBefore(hoy)) return hoy.plusDays(1);
                return hoy;
            }
            return last.map(r -> r.getIniciadoEn().plusHours(c.intervaloHoras())).orElse(LocalDateTime.now());
        } catch (Exception e) {
            return null;
        }
    }

    public List<Map<String, Object>> listar() {
        return repo.findAllByOrderByIniciadoEnDesc().stream().map(this::resumen).toList();
    }

    /** Ejecuciones terminadas con id mayor a uno dado (para avisos in-app). */
    public List<Map<String, Object>> eventos(Long desdeId) {
        long id = desdeId == null ? 0 : desdeId;
        return repo.findByIdGreaterThanOrderByIniciadoEnAsc(id).stream()
                .filter(r -> Respaldo.esTerminal(r.getEstado()))
                .map(this::resumen)
                .toList();
    }

    // ============================================================
    //  Respaldos (automáticos y manuales)
    // ============================================================

    /** Lanza un respaldo en segundo plano. Devuelve la fila creada (ya 'en_progreso'). */
    public Map<String, Object> iniciarRespaldo(String tipo, String motivo) {
        if (!Respaldo.TIPO_AUTOMATICO.equals(tipo) && !Respaldo.TIPO_MANUAL.equals(tipo)) {
            throw new IllegalArgumentException("El tipo de respaldo debe ser 'automatico' o 'manual'");
        }
        if (!db.esArchivo() && cfg.binDump() == null) {
            throw new IllegalArgumentException("No se encontró la herramienta de volcado (mysqldump o mariadb-dump). "
                    + "Indica la carpeta bin de tu instalación (MariaDB o MySQL) en esta misma sección y vuelve a intentar.");
        }
        if (!ocupado.compareAndSet(false, true)) {
            throw new IllegalStateException("Ya hay una operación en curso (" + operacion.get() + "). Espera a que termine.");
        }
        // Reservamos el bloqueo ANTES de preparar la fila; si algo falla aquí hay que
        // soltarlo para no dejar el sistema bloqueado para siempre.
        operacion.set(Respaldo.TIPO_AUTOMATICO.equals(tipo) ? OPER_RESPALDO_AUTO : OPER_RESPALDO_MANUAL);
        enCursoId.set(0);
        try {
            Path dir = Respaldo.TIPO_AUTOMATICO.equals(tipo) ? cfg.dirAuto() : cfg.dirManual();

            Respaldo r = new Respaldo();
            r.setTipo(tipo);
            r.setEstado(Respaldo.ESTADO_EN_PROGRESO);
            r.setMotivo(motivo == null ? Respaldo.MOTIVO_MANUAL : motivo);
            r.setArchivo(nombreArchivo(tipo));
            r.setRuta(dir.resolve(r.getArchivo()).toString());
            r.setMotor(db.conexion().motor());
            r.setVersion(VERSION);
            r.setIniciadoEn(LocalDateTime.now());
            r.setTokenEjecucion(UUID.randomUUID().toString());
            r = repo.save(r);

            final Long id = r.getId();
            enCursoId.set(id);
            worker.submit(() -> ejecutarGuarded(id, () -> ejecutarRespaldo(id)));
            return resumen(id);
        } catch (RuntimeException e) {
            liberar();
            throw e;
        }
    }

    private void ejecutarRespaldo(Long id) {
        if (db.esArchivo()) {
            ejecutarRespaldoSqlite(id);
            return;
        }
        Respaldo r = repo.findById(id).orElseThrow();
        long t0 = System.currentTimeMillis();
        Path final1 = Path.of(r.getRuta());
        Path part = final1.resolveSibling(final1.getFileName() + ".part");
        Path tmp = null;
        try {
            Files.createDirectories(final1.getParent());
            Files.deleteIfExists(part);

            String bin = cfg.binDump();
            if (bin == null) throw new IllegalStateException("No se encontró la herramienta de volcado (mysqldump/mariadb-dump)");
            tmp = archivoDefaults();

            List<String> cmd = new ArrayList<>();
            cmd.add(bin);
            cmd.add("--defaults-extra-file=" + tmp);
            cmd.add("--single-transaction");
            cmd.add("--quick");
            // Sin LOCK TABLES/UNLOCK TABLES en el volcado: restaurarlo exige el
            // privilegio LOCK TABLES, que el usuario de la app normalmente NO
            // tiene. --single-transaction ya garantiza una copia consistente.
            cmd.add("--skip-add-locks");
            cmd.add("--skip-lock-tables");
            cmd.add("--default-character-set=utf8mb4");
            cmd.add("--hex-blob");
            cmd.add("--add-drop-table");
            cmd.add("--routines");
            cmd.add("--triggers");
            // NO volcar las tablas del propio módulo de respaldos: si se incluyen,
            // al restaurar se reemplaza el historial por la foto del momento del
            // volcado (se perdería la fila de la propia restauración y la del
            // respaldo de seguridad previo, y reaparecerían filas 'en_progreso'
            // que parecerían interrumpidas). El resto de la base SÍ va completo.
            cmd.add("--ignore-table=" + base() + ".respaldos");
            cmd.add("--ignore-table=" + base() + ".config_respaldo");
            cmd.add(base());

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            Salida salida;
            try (OutputStream fileOut = Files.newOutputStream(part);
                 DigestOutputStream dos = new DigestOutputStream(fileOut, sha);
                 GZIPOutputStream gz = new GZIPOutputStream(dos, 1 << 16)) {
                salida = ejecutar(cmd, null, gz, TIMEOUT_DUMP_MIN);
            }
            if (salida.codigo() != 0) {
                throw new IllegalStateException("La herramienta de volcado terminó con código " + salida.codigo()
                        + (salida.error().isBlank() ? "" : ": " + corto(salida.error())));
            }
            long tam = Files.size(part);
            if (tam <= 0) throw new IllegalStateException("El volcado quedó vacío");

            Files.move(part, final1, StandardCopyOption.REPLACE_EXISTING);
            r.setEstado(Respaldo.ESTADO_COMPLETADO);
            r.setTamanoBytes(tam);
            r.setChecksumSha256(hex(sha.digest()));
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje("Respaldo completado correctamente.");
            repo.save(r);
            log.info("Respaldo {} ({}) completado: {} ({} bytes)", id, r.getTipo(), final1.getFileName(), tam);

            if (Respaldo.TIPO_AUTOMATICO.equals(r.getTipo())) aplicarRetencion();
        } catch (Exception e) {
            log.error("Respaldo {} falló: {}", id, e.getMessage(), e);
            borrarSilencioso(part);
            r.setEstado(Respaldo.ESTADO_FALLO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje(corto(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            repo.save(r);
        } finally {
            borrarSilencioso(tmp);
        }
    }

    /**
     * Respaldo SQLite: {@code VACUUM INTO} genera una copia consistente del archivo
     * de la base y luego se comprime a {@code .db.gz} (con SHA-256), igual patrón
     * que los motores de servidor pero sin herramientas externas.
     */
    private void ejecutarRespaldoSqlite(Long id) {
        Respaldo r = repo.findById(id).orElseThrow();
        long t0 = System.currentTimeMillis();
        Path final1 = Path.of(r.getRuta());
        Path part = final1.resolveSibling(final1.getFileName() + ".part");
        Path volcado = null;
        try {
            Files.createDirectories(final1.getParent());
            Files.deleteIfExists(part);

            // 1) Foto consistente de la base con VACUUM INTO (archivo nuevo).
            volcado = Files.createTempFile("crow-sqlite-", ".db");
            Files.deleteIfExists(volcado);
            String destino = volcado.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
            try (java.sql.Connection c = db.nuevaConexion(); java.sql.Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + destino + "'");
            }

            // 2) Comprimir a .part calculando el SHA-256 del .gz.
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (OutputStream fileOut = Files.newOutputStream(part);
                 DigestOutputStream dos = new DigestOutputStream(fileOut, sha);
                 GZIPOutputStream gz = new GZIPOutputStream(dos, 1 << 16);
                 InputStream in = Files.newInputStream(volcado)) {
                in.transferTo(gz);
            }
            long tam = Files.size(part);
            if (tam <= 0) throw new IllegalStateException("El respaldo quedó vacío");

            Files.move(part, final1, StandardCopyOption.REPLACE_EXISTING);
            r.setEstado(Respaldo.ESTADO_COMPLETADO);
            r.setTamanoBytes(tam);
            r.setChecksumSha256(hex(sha.digest()));
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje("Respaldo de SQLite completado correctamente.");
            repo.save(r);
            log.info("Respaldo SQLite {} ({}) completado: {} ({} bytes)", id, r.getTipo(), final1.getFileName(), tam);

            if (Respaldo.TIPO_AUTOMATICO.equals(r.getTipo())) aplicarRetencion();
        } catch (Exception e) {
            log.error("Respaldo SQLite {} falló: {}", id, e.getMessage(), e);
            borrarSilencioso(part);
            r.setEstado(Respaldo.ESTADO_FALLO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje(corto(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            repo.save(r);
        } finally {
            borrarSilencioso(volcado);
        }
    }

    /** Conserva los últimos N respaldos automáticos completados; borra archivo y fila de los viejos. */
    private void aplicarRetencion() {
        int n = cfg.cargar().retencion();
        if (n <= 0) return;
        List<Respaldo> lista = repo.findTerminadosPorTipo(Respaldo.TIPO_AUTOMATICO, Respaldo.ESTADO_COMPLETADO);
        for (int i = n; i < lista.size(); i++) {
            Respaldo viejo = lista.get(i);
            borrarSilencioso(Path.of(viejo.getRuta()));
            repo.delete(viejo);
            log.info("Retención: se eliminó el respaldo automático antiguo {}", viejo.getArchivo());
        }
    }

    // ============================================================
    //  Restauración e importación (destructivas: confirmación + copia previa)
    // ============================================================

    public Map<String, Object> restaurar(Long id, String confirmacion) {
        Respaldo origen = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El respaldo no existe"));
        if (!Respaldo.ESTADO_COMPLETADO.equals(origen.getEstado())) {
            throw new IllegalArgumentException("Solo se puede restaurar un respaldo completado");
        }
        Path src = Path.of(origen.getRuta());
        if (!Files.isRegularFile(src)) {
            throw new IllegalArgumentException("El archivo del respaldo ya no está en disco: " + origen.getRuta());
        }
        exigirConfirmacion(confirmacion);
        return encolarOperacion(OPER_RESTAURACION, "restauracion", src, "Restauración de " + origen.getArchivo());
    }

    public Map<String, Object> importar(String nombreOriginal, InputStream contenido, String confirmacion) {
        exigirConfirmacion(confirmacion);
        if (contenido == null) throw new IllegalArgumentException("No se recibió ningún archivo");
        // Rechazo temprano: no guardamos el archivo subido si ya hay algo en curso.
        exigirLibre("importar");
        Path destino;
        try {
            Path dir = cfg.dirManual();
            String limpio = sanear(nombreOriginal);
            destino = dir.resolve("importado_" + LocalDateTime.now().format(SELLO) + "_" + limpio);
            Files.copy(contenido, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo guardar el archivo importado: " + e.getMessage());
        }
        try {
            return encolarOperacion(OPER_IMPORTACION, "importacion", destino, "Importación de " + destino.getFileName());
        } catch (RuntimeException e) {
            // Si perdimos la carrera por el bloqueo, no dejamos el archivo huérfano.
            borrarSilencioso(destino);
            throw e;
        }
    }

    private Map<String, Object> encolarOperacion(String oper, String motivo, Path archivo, String nota) {
        if (!ocupado.compareAndSet(false, true)) {
            throw new IllegalStateException("Ya hay una operación en curso (" + operacion.get() + "). Espera a que termine.");
        }
        operacion.set(oper);
        enCursoId.set(0);
        try {
            if (archivo == null || !Files.isRegularFile(archivo)) {
                throw new IllegalArgumentException("El archivo indicado no existe");
            }
            Respaldo r = new Respaldo();
            r.setTipo(Respaldo.TIPO_MANUAL);
            r.setEstado(Respaldo.ESTADO_EN_PROGRESO);
            r.setMotivo(motivo);
            r.setArchivo(archivo.getFileName().toString());
            r.setRuta(archivo.toString());
            r.setMotor(db.conexion().motor());
            r.setVersion(VERSION);
            r.setIniciadoEn(LocalDateTime.now());
            r.setTokenEjecucion(UUID.randomUUID().toString());
            r.setMensaje(nota);
            r = repo.save(r);

            final Long id = r.getId();
            final boolean gz = archivo.getFileName().toString().toLowerCase().endsWith(".gz");
            enCursoId.set(id);
            worker.submit(() -> ejecutarGuarded(id, () -> ejecutarRestauracion(id, archivo, gz, oper)));
            return resumen(id);
        } catch (RuntimeException e) {
            liberar();
            throw e;
        }
    }

    private void ejecutarRestauracion(Long id, Path src, boolean gz, String oper) {
        if (db.esArchivo()) {
            ejecutarRestauracionSqlite(id, src, gz, oper);
            return;
        }
        Respaldo r = repo.findById(id).orElseThrow();
        long t0 = System.currentTimeMillis();
        Path tmp = null;
        try {
            // 1) Red de seguridad: respaldo de la BD actual ANTES de tocarla.
            log.info("Antes de {} se hace un respaldo de seguridad", oper);
            Long seguridadId = respaldoSeguroSincrono(OPER_IMPORTACION.equals(oper)
                    ? Respaldo.MOTIVO_PRE_IMPORTACION : Respaldo.MOTIVO_PRE_RESTAURACION);
            if (seguridadId == null) {
                throw new IllegalStateException("No se pudo crear el respaldo de seguridad previo; "
                        + "se cancela la operación para no perder datos.");
            }
            repo.findById(seguridadId).ifPresent(seg -> log.info("Respaldo de seguridad #{} listo", seg.getId()));

            // 2) Restaurar (el cliente lee el SQL por stdin; nunca DROP DATABASE).
            String bin = cfg.binCliente();
            if (bin == null) throw new IllegalStateException("No se encontró el cliente de la base de datos (mysql/mariadb) para restaurar");
            tmp = archivoDefaults();

            List<String> cmd = new ArrayList<>();
            cmd.add(bin);
            cmd.add("--defaults-extra-file=" + tmp);
            cmd.add("--default-character-set=utf8mb4");
            cmd.add(base());

            Salida salida;
            try (InputStream raw = new BufferedInputStream(Files.newInputStream(src));
                 InputStream in = gz ? new GZIPInputStream(raw, 1 << 16) : raw) {
                salida = ejecutar(cmd, in, null, TIMEOUT_DUMP_MIN);
            }
            if (salida.codigo() != 0) {
                throw new IllegalStateException("La restauración terminó con código " + salida.codigo()
                        + (salida.error().isBlank() ? "" : ": " + corto(salida.error())));
            }
            r.setEstado(Respaldo.ESTADO_COMPLETADO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje((r.getMensaje() == null ? "" : r.getMensaje() + " · ")
                    + "Completada. Se hizo un respaldo de seguridad antes (id " + seguridadId + ").");
            repo.save(r);
            log.info("{} completada: {}", oper, src.getFileName());
        } catch (Exception e) {
            log.error("{} falló: {}", oper, e.getMessage(), e);
            r.setEstado(Respaldo.ESTADO_FALLO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje(corto(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            repo.save(r);
        } finally {
            borrarSilencioso(tmp);
        }
    }

    /**
     * Restauración SQLite EN CALIENTE: se descomprime el respaldo a un archivo
     * temporal y, con {@code ATTACH}, se copian sus tablas sobre la base viva
     * dentro de una transacción (no se puede reemplazar el archivo: la app lo tiene
     * abierto). Se preservan las tablas del propio módulo de respaldos
     * ({@code respaldos} y {@code config_respaldo}) para no perder el historial.
     */
    private void ejecutarRestauracionSqlite(Long id, Path src, boolean gz, String oper) {
        Respaldo r = repo.findById(id).orElseThrow();
        long t0 = System.currentTimeMillis();
        Path staging = null;
        try {
            // 1) Red de seguridad: respaldo de la base actual ANTES de tocarla.
            Long seguridadId = respaldoSeguroSincrono(OPER_IMPORTACION.equals(oper)
                    ? Respaldo.MOTIVO_PRE_IMPORTACION : Respaldo.MOTIVO_PRE_RESTAURACION);
            if (seguridadId == null) {
                throw new IllegalStateException("No se pudo crear el respaldo de seguridad previo; "
                        + "se cancela la operación para no perder datos.");
            }

            // 2) Descomprimir el respaldo a un archivo temporal.
            staging = Files.createTempFile("crow-restore-", ".db");
            try (InputStream raw = new BufferedInputStream(Files.newInputStream(src));
                 InputStream in = gz ? new GZIPInputStream(raw, 1 << 16) : raw;
                 OutputStream out = Files.newOutputStream(staging)) {
                in.transferTo(out);
            }

            // 3) Validar y copiar sobre la base viva.
            int tablas = copiarDesdeSqlite(staging);

            r.setEstado(Respaldo.ESTADO_COMPLETADO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje((r.getMensaje() == null ? "" : r.getMensaje() + " · ")
                    + "Completada (" + tablas + " tablas). Se hizo un respaldo de seguridad antes (id "
                    + seguridadId + ").");
            repo.save(r);
            log.info("{} SQLite completada: {} ({} tablas)", oper, src.getFileName(), tablas);
        } catch (Exception e) {
            log.error("{} SQLite falló: {}", oper, e.getMessage(), e);
            r.setEstado(Respaldo.ESTADO_FALLO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setDuracionMs(System.currentTimeMillis() - t0);
            r.setMensaje(corto(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            repo.save(r);
        } finally {
            borrarSilencioso(staging);
        }
    }

    /** Tablas del módulo de respaldos que se conservan al restaurar (no se pisan). */
    private static final java.util.Set<String> TABLAS_RESPALDO = java.util.Set.of("respaldos", "config_respaldo");

    /**
     * Copia todas las tablas de {@code origen} (una base SQLite) sobre la base viva:
     * {@code ATTACH} + borrar/insertar por tabla, en una transacción y con las
     * claves foráneas desactivadas. Devuelve cuántas tablas copió.
     */
    private int copiarDesdeSqlite(Path origen) throws Exception {
        int copiadas = 0;
        try (java.sql.Connection c = db.nuevaConexion()) {
            // Las FK se desactivan antes de la transacción (dentro no tienen efecto).
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=OFF");
                st.execute("ATTACH DATABASE '" + rutaSql(origen.toAbsolutePath()) + "' AS bk");
            }
            try {
                // Validar que el archivo es una base de esta app.
                try (java.sql.Statement st = c.createStatement();
                     java.sql.ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) FROM bk.sqlite_master WHERE type='table' AND name='titulos'")) {
                    if (!rs.next() || rs.getInt(1) == 0) {
                        throw new IllegalStateException("El archivo no parece una base de datos de esta aplicación.");
                    }
                }

                List<String> tablas = new ArrayList<>();
                try (java.sql.Statement st = c.createStatement();
                     java.sql.ResultSet rs = st.executeQuery(
                             "SELECT name FROM bk.sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                    while (rs.next()) tablas.add(rs.getString(1));
                }
                if (tablas.isEmpty()) throw new IllegalStateException("La base de datos del respaldo está vacía.");

                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (java.sql.Statement st = c.createStatement()) {
                    for (String t : tablas) {
                        if (TABLAS_RESPALDO.contains(t)) continue;
                        List<String> cols = columnasComunes(c, t);
                        if (cols.isEmpty()) continue;
                        String lista = String.join(", ", cols.stream().map(x -> q(x)).toList());
                        st.execute("DELETE FROM main." + q(t));
                        st.execute("INSERT INTO main." + q(t) + " (" + lista + ") SELECT " + lista
                                + " FROM bk." + q(t));
                        copiadas++;
                    }
                }
                c.commit();
                c.setAutoCommit(auto);
            } finally {
                try (java.sql.Statement st = c.createStatement()) {
                    st.execute("DETACH DATABASE bk");
                    st.execute("PRAGMA foreign_keys=ON");
                }
            }
        }
        return copiadas;
    }

    /** Columnas presentes tanto en la base viva como en la del respaldo (orden de la viva). */
    private static List<String> columnasComunes(java.sql.Connection c, String tabla) throws Exception {
        List<String> main = columnas(c, "main", tabla);
        List<String> bk = columnas(c, "bk", tabla);
        List<String> out = new ArrayList<>();
        for (String col : main) {
            if (bk.contains(col)) out.add(col);
        }
        return out;
    }

    private static List<String> columnas(java.sql.Connection c, String esquema, String tabla) throws Exception {
        List<String> cols = new ArrayList<>();
        try (java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("PRAGMA " + esquema + ".table_info(" + q(tabla) + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String rutaSql(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/').replace("'", "''");
    }

    /** Respaldo manual síncrono (para la red de seguridad). Devuelve el id, o null si falla. */
    private Long respaldoSeguroSincrono(String motivo) {
        try {
            if (!db.esArchivo() && cfg.binDump() == null) return null;
            Respaldo s = new Respaldo();
            s.setTipo(Respaldo.TIPO_MANUAL);
            s.setEstado(Respaldo.ESTADO_EN_PROGRESO);
            s.setMotivo(motivo);
            s.setArchivo(nombreArchivo(Respaldo.TIPO_MANUAL));
            s.setRuta(cfg.dirManual().resolve(s.getArchivo()).toString());
            s.setMotor(db.conexion().motor());
            s.setVersion(VERSION);
            s.setIniciadoEn(LocalDateTime.now());
            s.setTokenEjecucion(UUID.randomUUID().toString());
            s = repo.save(s);
            ejecutarRespaldo(s.getId());
            Respaldo recargado = repo.findById(s.getId()).orElseThrow();
            return Respaldo.ESTADO_COMPLETADO.equals(recargado.getEstado()) ? s.getId() : null;
        } catch (Exception e) {
            log.error("Respaldo de seguridad falló: {}", e.getMessage(), e);
            return null;
        }
    }

    // ============================================================
    //  Verificación, integridad y gestión de archivos
    // ============================================================

    /** Recalcula el SHA-256 del archivo y lo compara con el guardado. */
    public Map<String, Object> verificar(Long id) {
        Respaldo r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("El respaldo no existe"));
        Path p = Path.of(r.getRuta());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("archivo", r.getArchivo());
        if (!Files.isRegularFile(p)) {
            r.setMensaje("Verificación: el archivo no está en disco (" + r.getRuta() + ")");
            repo.save(r);
            out.put("ok", false);
            out.put("mensaje", r.getMensaje());
            return out;
        }
        try {
            String actual = sha256(p);
            boolean ok = actual.equalsIgnoreCase(r.getChecksumSha256());
            r.setVerificadoEn(LocalDateTime.now());
            r.setMensaje(ok ? "Verificación correcta: el archivo coincide con su checksum."
                    : "Verificación FALLIDA: el archivo cambió (¿se editó a mano o se corrompió?).");
            repo.save(r);
            out.put("ok", ok);
            out.put("esperado", r.getChecksumSha256());
            out.put("calculado", actual);
            out.put("mensaje", r.getMensaje());
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("mensaje", "No se pudo verificar: " + e.getMessage());
            return out;
        }
    }

    /**
     * Control de integridad. Se ejecuta al arrancar y a demanda:
     * - Marca 'interrumpido' todo lo que quedó 'en_progreso' (apagón o cierre abrupto).
     * - Borra archivos *.part huérfanos.
     * - Avisa de respaldos cuyo archivo ya no está en disco.
     */
    public Map<String, Object> revisarIntegridad() {
        exigirLibre("revisar la integridad");
        int interrumpidos = 0;
        for (Respaldo r : repo.findByEstado(Respaldo.ESTADO_EN_PROGRESO)) {
            r.setEstado(Respaldo.ESTADO_INTERRUMPIDO);
            r.setTerminadoEn(LocalDateTime.now());
            r.setMensaje("Interrumpido: la app se cerró o apagó antes de terminar. "
                    + "El archivo a medias se descartó; vuelve a lanzarlo si lo necesitas.");
            repo.save(r);
            interrumpidos++;
        }
        int parts = limpiarParts();
        int ausentes = 0;
        for (Respaldo r : repo.findAllByOrderByIniciadoEnDesc()) {
            if (Respaldo.ESTADO_COMPLETADO.equals(r.getEstado()) && !Files.isRegularFile(Path.of(r.getRuta()))) {
                ausentes++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interrumpidos", interrumpidos);
        out.put("archivos_parciales_borrados", parts);
        out.put("respaldos_sin_archivo", ausentes);
        if (interrumpidos > 0) {
            log.warn("Integridad de respaldos: {} ejecución(es) interrumpida(s) por cierre abrupto; "
                    + "{} archivo(s) parcial(es) descartado(s)", interrumpidos, parts);
        }
        return out;
    }

    private int limpiarParts() {
        int n = 0;
        for (Path dir : List.of(cfg.dirAuto(), cfg.dirManual())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.part")) {
                for (Path p : ds) {
                    borrarSilencioso(p);
                    n++;
                }
            } catch (IOException ignored) {
                // carpeta inexistente o sin permisos: nada que limpiar
            }
        }
        return n;
    }

    /** Borra un respaldo (archivo + fila). Exige confirmar con el nombre del archivo. */
    public void eliminar(Long id, String confirmacion) {
        Respaldo r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("El respaldo no existe"));
        if (confirmacion == null || !confirmacion.trim().equals(r.getArchivo())) {
            throw new IllegalArgumentException("Para borrar escribe el nombre exacto del archivo: " + r.getArchivo());
        }
        if (ocupado() && enCursoId.get() == id) {
            throw new IllegalStateException("No se puede borrar mientras esa operación está en curso");
        }
        borrarSilencioso(Path.of(r.getRuta()));
        repo.delete(r);
        log.info("Respaldo {} eliminado (archivo y registro)", r.getArchivo());
    }

    /** Ruta en disco de un respaldo (para descargar), o null si no está. */
    public Path archivoDe(Long id) {
        Respaldo r = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("El respaldo no existe"));
        Path p = Path.of(r.getRuta());
        return Files.isRegularFile(p) ? p : null;
    }

    public Respaldo buscar(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("El respaldo no existe"));
    }

    // ============================================================
    //  Utilidades internas
    // ============================================================

    private void ejecutarGuarded(Long id, Runnable tarea) {
        try {
            tarea.run();
        } catch (Throwable t) {
            log.error("Operación de respaldo #{} falló de forma inesperada: {}", id, t.getMessage(), t);
            repo.findById(id).ifPresent(r -> {
                r.setEstado(Respaldo.ESTADO_FALLO);
                r.setTerminadoEn(LocalDateTime.now());
                r.setMensaje(corto(String.valueOf(t.getMessage())));
                repo.save(r);
            });
        } finally {
            liberar();
        }
    }

    /** Suelta el bloqueo global de operaciones (se usa en el finally y en fallos previos al worker). */
    public void liberar() {
        ocupado.set(false);
        operacion.set(null);
        enCursoId.set(0);
    }

    private void exigirConfirmacion(String confirmacion) {
        String esperado = base();
        if (confirmacion == null || !confirmacion.trim().equalsIgnoreCase(esperado)) {
            throw new IllegalArgumentException("Operación cancelada: para continuar escribe el nombre exacto de la base "
                    + "de datos (" + esperado + "). Es irreversible y reemplaza los datos actuales.");
        }
    }

    private String nombreArchivo(String tipo) {
        String prefijo = Respaldo.TIPO_AUTOMATICO.equals(tipo) ? "respaldo_auto_" : "respaldo_manual_";
        String ext = db.esArchivo() ? ".db.gz" : ".sql.gz";
        String nombre = prefijo + LocalDateTime.now().format(SELLO) + ext;
        Path dir = Respaldo.TIPO_AUTOMATICO.equals(tipo) ? cfg.dirAuto() : cfg.dirManual();
        if (Files.exists(dir.resolve(nombre))) {
            nombre = prefijo + LocalDateTime.now().format(SELLO) + "_" + (System.nanoTime() % 1000) + ext;
        }
        return nombre;
    }

    /** Archivo temporal [client] con las credenciales (no aparecen en la línea de comandos). */
    private Path archivoDefaults() throws IOException {
        Path tmp = Files.createTempFile("crow-db-", ".cnf");
        try {
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows: los permisos POSIX no aplican; el archivo se borra al terminar
        }
        String host = db.conexion().host();
        String puerto = db.conexion().puerto();
        String usuario = env.getProperty("spring.datasource.username", "");
        String password = env.getProperty("spring.datasource.password", "");
        String contenido = "[client]\n"
                + "host=" + opt(host) + "\n"
                + "port=" + opt(puerto) + "\n"
                + "user=" + opt(usuario) + "\n"
                + "password=" + opt(password) + "\n";
        Files.writeString(tmp, contenido);
        return tmp;
    }

    private String base() {
        String b = db.conexion().base();
        if (b == null || b.isBlank()) throw new IllegalStateException("No se pudo determinar el nombre de la base de datos");
        return b;
    }

    /** Ejecuta un proceso; opcionalmente le pasa stdin y recoge stdout. Drena stdout y
     *  stderr en hilos aparte para no bloquearse si alguno llena el buffer. */
    private Salida ejecutar(List<String> cmd, InputStream stdin, OutputStream stdout, long timeoutMin) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cfg.raizApp().toFile());
        Process p = pb.start();

        StringBuilder err = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (InputStream es = p.getErrorStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = es.read(buf)) > 0) err.append(new String(buf, 0, n));
            } catch (IOException ignored) {
                // proceso cerrado: no hay más que leer
            }
        }, "respaldo-stderr");
        errThread.setDaemon(true);

        Thread outThread = new Thread(() -> {
            try (InputStream os = p.getInputStream()) {
                os.transferTo(stdout != null ? stdout : OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // proceso cerrado: no hay más que leer
            }
        }, "respaldo-stdout");
        outThread.setDaemon(true);

        errThread.start();
        outThread.start();

        try (OutputStream os = p.getOutputStream()) {
            if (stdin != null) stdin.transferTo(os);
        } catch (IOException ignored) {
            // el cliente puede cerrar stdin temprano (error): se reporta por el código de salida
        }

        boolean termino;
        try {
            termino = p.waitFor(timeoutMin, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Operación interrumpida");
        }
        if (!termino) {
            p.destroyForcibly();
            throw new IOException("La operación tardó más de " + timeoutMin + " minutos y se canceló");
        }
        unir(errThread);
        unir(outThread);
        return new Salida(p.exitValue(), err.toString());
    }

    private static void unir(Thread t) {
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> resumen(Long id) {
        return resumen(repo.findById(id).orElseThrow());
    }

    private Map<String, Object> resumen(Respaldo r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("tipo", r.getTipo());
        m.put("estado", r.getEstado());
        m.put("motivo", r.getMotivo());
        m.put("archivo", r.getArchivo());
        m.put("ruta", r.getRuta());
        m.put("tamano_bytes", r.getTamanoBytes());
        m.put("checksum_sha256", r.getChecksumSha256());
        m.put("motor", r.getMotor());
        m.put("version", r.getVersion());
        m.put("mensaje", r.getMensaje());
        m.put("iniciado_en", r.getIniciadoEn());
        m.put("terminado_en", r.getTerminadoEn());
        m.put("duracion_ms", r.getDuracionMs());
        m.put("verificado_en", r.getVerificadoEn());
        m.put("existe", r.getRuta() != null && Files.isRegularFile(Path.of(r.getRuta())));
        m.put("operacion", esOperacion(r.getMotivo()));
        return m;
    }

    private static boolean esOperacion(String motivo) {
        return "restauracion".equals(motivo) || "importacion".equals(motivo);
    }

    private static String opt(String v) {
        String s = v == null ? "" : v;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String sanear(String nombre) {
        String n = nombre == null ? "importado.sql" : Path.of(nombre).getFileName().toString();
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isBlank() ? "importado.sql" : n;
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(p), md)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static String corto(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= 1500 ? t : t.substring(0, 1500) + "…";
    }

    private static void borrarSilencioso(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // nada que hacer
        }
    }

    private record Salida(int codigo, String error) {}
}
