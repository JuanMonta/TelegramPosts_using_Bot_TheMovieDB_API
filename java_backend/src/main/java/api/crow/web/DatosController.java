package api.crow.web;

import api.crow.config.ConfigBootstrapDb;
import api.crow.config.ConfigBootstrapDb.Bootstrap;
import api.crow.config.MotorBd;
import api.crow.domain.ConfigRespaldo;
import api.crow.service.BaseDatosAdminService;
import api.crow.service.BaseDatosService;
import api.crow.service.ConfigRespaldoService;
import api.crow.service.MigracionService;
import api.crow.service.RespaldoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pestaña "Datos": estado de la conexión, configuración de respaldos, historial,
 * respaldos manuales, restauración/importación (destructivas, con confirmación),
 * verificación, descarga, control de integridad y el asistente de base de datos
 * (probar conexión de administrador y crear la base en el primer arranque).
 *
 * La conexión usa config-db.yaml, que edita el asistente. La contraseña real nunca
 * sale hacia el cliente.
 */
@RestController
@RequestMapping("/api/datos")
@RequiredArgsConstructor
public class DatosController {

    private final BaseDatosService db;
    private final ConfigRespaldoService cfg;
    private final RespaldoService respaldos;
    private final ConfigBootstrapDb cfgBootstrap;
    private final BaseDatosAdminService admin;
    private final MigracionService migracion;

    // ---- estado y conexión ----

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        boolean hayBd = db.disponible();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bd_disponible", hayBd);
        out.put("conexion", db.conexion());
        out.put("inventario", db.inventario(cfg, hayBd));
        out.put("config", hayBd ? configMap() : configMapSinBd());
        out.put("estado", hayBd ? respaldos.estado() : respaldos.estadoSinBd());
        out.put("bootstrap", admin.resumen());
        return out;
    }

    @GetMapping("/conexion")
    public Map<String, Object> conexion() {
        return db.inventario(cfg);
    }

    @PostMapping("/conexion/probar")
    public BaseDatosService.Prueba probar() {
        return db.probar();
    }

    // ---- asistente de base de datos (primer arranque) ----

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bd_disponible", db.disponible());
        out.put("config", admin.resumen());
        return out;
    }

    /** Prueba la conexión de administrador contra el servidor (sin base seleccionada). */
    @PostMapping("/bootstrap/probar-admin")
    public BaseDatosAdminService.PruebaAdmin probarAdmin(@RequestBody(required = false) Map<String, Object> body) {
        return admin.probarAdmin(bootstrapDesde(body));
    }

    /**
     * Crea la base de datos (CREATE DATABASE + esquema + usuario de la app) y guarda
     * la conexión. Nunca borra: si la base ya existe, se rechaza.
     */
    @PostMapping("/bootstrap/crear")
    public ResponseEntity<?> crearBase(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Bootstrap b = bootstrapDesde(body);
            ResponseEntity<?> bloqueo = rechazoCambioMotor(body, b);
            if (bloqueo != null) return bloqueo;
            return ResponseEntity.ok(admin.crear(b));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * ESCENARIO B: la base ya existe (creada por el usuario con un usuario dedicado)
     * y solo se crean las tablas. No crea la base, ni el usuario, ni permisos.
     */
    @PostMapping("/bootstrap/crear-tablas")
    public ResponseEntity<?> crearTablas(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Bootstrap b = bootstrapDesde(body);
            ResponseEntity<?> bloqueo = rechazoCambioMotor(body, b);
            if (bloqueo != null) return bloqueo;
            return ResponseEntity.ok(admin.crearTablas(b));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ---- migración de motor (asistida) ----

    /** Analiza origen y destino sin tocar nada: tablas, filas y avisos. */
    @PostMapping("/migracion/analizar")
    public ResponseEntity<?> analizarMigracion(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(migracion.analizar(destinoDesde(body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage() == null ? "No se pudo analizar el destino" : e.getMessage()));
        }
    }

    /**
     * Migra los datos al motor destino, verifica (conteo + checksum por tabla) y,
     * solo si todo cuadra, cambia la conexión. El origen nunca se borra.
     */
    @PostMapping("/migracion/migrar")
    public ResponseEntity<?> migrarMotor(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Bootstrap d = destinoDesde(body);
            boolean vaciar = booleano(body, "vaciar_destino", false);
            return ResponseEntity.ok(migracion.migrar(d, vaciar, texto(body, "confirmacion", null)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ---- configuración de respaldos ----

    @GetMapping("/config")
    public Map<String, Object> config() {
        return configMap();
    }

    @PutMapping("/config")
    public ResponseEntity<?> guardarConfig(@RequestBody(required = false) Map<String, Object> body) {
        try {
            // No se puede cambiar la programación (ni las carpetas de destino) mientras
            // hay un respaldo/restauración en curso.
            respaldos.exigirLibre("cambiar la programación de respaldos");
            ConfigRespaldoService.Config actual = cfg.cargar();
            ConfigRespaldoService.Config nueva = new ConfigRespaldoService.Config(
                    booleano(body, "auto_activo", actual.autoActivo()),
                    texto(body, "modo", actual.modo()),
                    entero(body, "intervalo_horas", actual.intervaloHoras()),
                    texto(body, "hora_diaria", actual.horaDiaria()),
                    texto(body, "dir_auto", actual.dirAuto()),
                    texto(body, "dir_manual", actual.dirManual()),
                    texto(body, "bin_dir", actual.binDir()),
                    entero(body, "retencion", actual.retencion()));
            cfg.guardar(nueva);
            Map<String, Object> out = configMap();
            out.put("inventario", db.inventario(cfg));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ---- historial y operaciones ----

    @GetMapping("/respaldos")
    public Map<String, Object> listar() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("respaldos", respaldos.listar());
        out.put("estado", respaldos.estado());
        return out;
    }

    @PostMapping("/respaldos")
    public ResponseEntity<?> crear(@RequestBody(required = false) Map<String, Object> body) {
        try {
            String tipo = texto(body, "tipo", "manual");
            return ResponseEntity.ok(respaldos.iniciarRespaldo(tipo, "manual"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/respaldos/{id}/restaurar")
    public ResponseEntity<?> restaurar(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(respaldos.restaurar(id, texto(body, "confirmacion", null)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/respaldos/{id}/verificar")
    public ResponseEntity<?> verificar(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(respaldos.verificar(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/respaldos/{id}/descargar")
    public ResponseEntity<?> descargar(@PathVariable Long id) throws IOException {
        Path archivo;
        String nombre;
        try {
            var r = respaldos.buscar(id);
            nombre = r.getArchivo();
            archivo = respaldos.archivoDe(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
        if (archivo == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "El archivo del respaldo ya no está en disco"));
        }
        byte[] datos = Files.readAllBytes(archivo);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(nombre, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(datos.length)
                .body(datos);
    }

    @DeleteMapping("/respaldos/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id, @RequestParam(required = false) String confirmacion) {
        try {
            respaldos.eliminar(id, confirmacion);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Importa un archivo .sql o .sql.gz y lo restaura. Destructivo: exige
     * confirmacion = nombre de la base de datos y hace un respaldo previo.
     */
    @PostMapping(value = "/importar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importar(@RequestParam("archivo") MultipartFile archivo,
                                      @RequestParam(value = "confirmacion", required = false) String confirmacion) {
        try {
            if (archivo == null || archivo.isEmpty()) {
                throw new IllegalArgumentException("No se recibió ningún archivo");
            }
            String nombre = archivo.getOriginalFilename();
            String bajo = nombre == null ? "" : nombre.toLowerCase();
            boolean valido = bajo.endsWith(".gz") || bajo.endsWith(".sql")
                    || bajo.endsWith(".db") || bajo.endsWith(".sqlite");
            if (!valido) {
                throw new IllegalArgumentException("El archivo debe ser un respaldo (.sql.gz, .db.gz, .sql, .db…).");
            }
            try (var in = archivo.getInputStream()) {
                return ResponseEntity.ok(respaldos.importar(nombre, in, confirmacion));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "No se pudo leer el archivo: " + e.getMessage()));
        }
    }

    @PostMapping("/integridad/revisar")
    public ResponseEntity<?> revisar() {
        try {
            return ResponseEntity.ok(respaldos.revisarIntegridad());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** Cambios desde un id dado (para avisos in-app). */
    @GetMapping("/eventos")
    public Map<String, Object> eventos(@RequestParam(value = "desdeId", required = false) Long desdeId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventos", respaldos.eventos(desdeId));
        out.put("estado", respaldos.estado());
        return out;
    }

    // ---- internos ----

    /**
     * Construye la configuración de bootstrap desde el body. Si 'password_admin' o
     * 'password_app' llegan vacíos, conserva el valor ya guardado (así la contraseña
     * nunca tiene que viajar de vuelta al cliente).
     */
    private Bootstrap bootstrapDesde(Map<String, Object> body) {
        Bootstrap actual = cfgBootstrap.cargar();
        String pAdmin = texto(body, "password_admin", "");
        if (pAdmin.isEmpty()) pAdmin = actual.passwordAdmin();
        String pApp = texto(body, "password_app", "");
        if (pApp.isEmpty()) pApp = actual.passwordApp();
        return new Bootstrap(true,
                api.crow.config.MotorBd.desde(texto(body, "motor", actual.motor())).clave(),
                texto(body, "host", actual.host()),
                texto(body, "puerto", actual.puerto()),
                texto(body, "usuario_admin", actual.usuarioAdmin()),
                pAdmin,
                texto(body, "nombre_base", actual.nombreBase()),
                texto(body, "usuario_app", actual.usuarioApp()),
                pApp,
                texto(body, "archivo", actual.archivo()));
    }

    /**
     * Configuración del DESTINO de la migración. A diferencia del asistente, los
     * campos en blanco NO heredan las contraseñas guardadas (el destino es otro sitio).
     */
    private Bootstrap destinoDesde(Map<String, Object> body) {
        Bootstrap actual = cfgBootstrap.cargar();
        return new Bootstrap(true,
                MotorBd.desde(texto(body, "motor", actual.motor())).clave(),
                texto(body, "host", actual.host()),
                texto(body, "puerto", actual.puerto()),
                texto(body, "usuario_admin", ""),
                texto(body, "password_admin", ""),
                texto(body, "nombre_base", ""),
                texto(body, "usuario_app", ""),
                texto(body, "password_app", ""),
                texto(body, "archivo", ""));
    }

    /**
     * Impide cambiar de motor "por accidente" desde el asistente: si la app ya está
     * conectada a una base y se pide otro motor, hay que usar la migración (o marcar
     * explícitamente "Empezar de cero").
     */
    private ResponseEntity<?> rechazoCambioMotor(Map<String, Object> body, Bootstrap b) {
        if (db.disponible() && b.motorEnum() != db.motor()
                && !booleano(body, "cambio_motor_confirmado", false)) {
            String activo = db.motor().etiqueta();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "La aplicación está usando " + activo + " ahora mismo. Para cambiar de motor sin perder datos, "
                    + "usa la sección \"Migrar de motor\". Si de verdad quieres empezar de cero con "
                    + b.motorEnum().etiqueta() + " (una base vacía), marca la casilla \"Empezar de cero\"."));
        }
        return null;
    }

    private Map<String, Object> configMap() {
        ConfigRespaldoService.Config c = cfg.cargar();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auto_activo", c.autoActivo());
        m.put("modo", c.modo());
        m.put("intervalo_horas", c.intervaloHoras());
        m.put("hora_diaria", c.horaDiaria());
        m.put("dir_auto", c.dirAuto());
        m.put("dir_manual", c.dirManual());
        m.put("bin_dir", c.binDir());
        m.put("retencion", c.retencion());
        m.put("proximo_automatico", respaldos.proximoAutomatico());
        m.put("clave_invalida", cfg.claveInvalida());
        m.put("bin_dump_disponible", cfg.binDump() != null);
        m.put("bin_cliente_disponible", cfg.binCliente() != null);
        m.put("nombre_base_datos", db.conexion().base());
        return m;
    }

    /** Igual que {@link #configMap()} pero sin tocar la BD (primer arranque). */
    private Map<String, Object> configMapSinBd() {
        ConfigRespaldoService.Config c = cfg.defecto();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auto_activo", c.autoActivo());
        m.put("modo", c.modo());
        m.put("intervalo_horas", c.intervaloHoras());
        m.put("hora_diaria", c.horaDiaria());
        m.put("dir_auto", c.dirAuto());
        m.put("dir_manual", c.dirManual());
        m.put("bin_dir", c.binDir());
        m.put("retencion", c.retencion());
        m.put("proximo_automatico", null);
        m.put("clave_invalida", cfg.claveInvalida());
        m.put("bin_dump_disponible", cfg.binDumpEn(c.binDir()) != null);
        m.put("bin_cliente_disponible", cfg.binClienteEn(c.binDir()) != null);
        m.put("nombre_base_datos", db.conexion().base());
        return m;
    }

    private static String texto(Map<String, Object> body, String clave, String defecto) {
        if (body == null || !body.containsKey(clave)) return defecto;
        Object v = body.get(clave);
        return v == null ? defecto : v.toString();
    }

    private static boolean booleano(Map<String, Object> body, String clave, boolean defecto) {
        if (body == null || !body.containsKey(clave)) return defecto;
        Object v = body.get(clave);
        if (v == null) return defecto;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private static int entero(Map<String, Object> body, String clave, int defecto) {
        if (body == null || !body.containsKey(clave)) return defecto;
        Object v = body.get(clave);
        if (v == null) return defecto;
        try {
            return (int) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(clave + " debe ser un número");
        }
    }
}
