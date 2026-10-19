package api.crow.service;

import api.crow.config.ConfigBootstrapDb;
import api.crow.config.MotorBd;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Estado de la conexión a la base de datos (solo lectura) y prueba de conexión.
 *
 * La verdad de los parámetros vive en application.yaml / application-local.yaml
 * (perfil 'local'). Aquí NUNCA se devuelve la contraseña: solo host, puerto,
 * base, usuario y driver, más el resultado de una conexión de prueba.
 *
 * Editar estos parámetros se hace en el yaml y requiere reiniciar el backend;
 * el diseño queda preparado para añadir otros gestores (el parser entiende
 * también jdbc:mysql:// y jdbc:postgresql:// sin cambios en la UI).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaseDatosService {

    private static final int TIMEOUT_CONEXION_MS = 6_000;

    private final Environment env;

    public record Conexion(String url, String motor, String host, String puerto, String base,
                           String usuario, String driver) {}

    public record Prueba(boolean ok, String mensaje, String version, String base,
                         Long tablas, Double tamanoMb, Long latenciaMs) {}

    /** Motor de la conexión vigente, según la URL del datasource. */
    public MotorBd motor() {
        return MotorBd.desdeUrl(prop("spring.datasource.url", ""));
    }

    /** true si la conexión vigente es SQLite (motor de archivo). */
    public boolean esArchivo() {
        return motor() == MotorBd.SQLITE;
    }

    /** Ruta del archivo de la base SQLite vigente ("" si no aplica). */
    public String archivoSqlite() {
        return ConfigBootstrapDb.archivoDeUrlSqlite(prop("spring.datasource.url", ""));
    }

    /** URL JDBC vigente. */
    public String url() {
        return prop("spring.datasource.url", "");
    }

    /**
     * Abre una conexión DIRECTA (fuera del pool), con las credenciales vigentes.
     * Para SQLite usa el archivo; para los motores de servidor, usuario/contraseña.
     */
    public Connection nuevaConexion() throws SQLException {
        String url = url();
        Properties p = new Properties();
        String usuario = prop("spring.datasource.username", "");
        String password = prop("spring.datasource.password", "");
        if (usuario != null && !usuario.isBlank()) p.setProperty("user", usuario);
        if (password != null) p.setProperty("password", password);
        return motor().conectar(url, p);
    }

    /** Datos de conexión vigentes, sin secretos. */
    public Conexion conexion() {
        String url = prop("spring.datasource.url", "");
        String usuario = prop("spring.datasource.username", "");
        String driver = prop("spring.datasource.driver-class-name", "");
        String motor = "mariadb";
        String host = "";
        String puerto = "";
        String base = "";

        // SQLite: la "conexión" es un archivo; no hay host, puerto ni usuario.
        if (url.startsWith(MotorBd.SQLITE.prefijoUrl())) {
            String archivo = ConfigBootstrapDb.archivoDeUrlSqlite(url);
            return new Conexion(url, MotorBd.SQLITE.clave(), "", "",
                    ConfigBootstrapDb.nombreDeArchivo(archivo), "", driver);
        }

        // jdbc:<motor>://<host>[:<puerto>]/<base>[?opciones]
        if (url.startsWith("jdbc:")) {
            String resto = url.substring("jdbc:".length());
            int sep = resto.indexOf("://");
            if (sep > 0) {
                motor = resto.substring(0, sep);
                String cola = resto.substring(sep + 3);
                int barra = cola.indexOf('/');
                String autoridad = barra >= 0 ? cola.substring(0, barra) : cola;
                base = barra >= 0 ? cola.substring(barra + 1) : "";
                int q = base.indexOf('?');
                if (q >= 0) base = base.substring(0, q);
                int dosPuntos = autoridad.indexOf(':');
                if (dosPuntos >= 0) {
                    host = autoridad.substring(0, dosPuntos);
                    puerto = autoridad.substring(dosPuntos + 1);
                } else {
                    host = autoridad;
                    puerto = "3306";
                }
            }
        }
        return new Conexion(url, motor, host, puerto, base, usuario, driver);
    }

    /** Abre una conexión real y devuelve versión, base y tamaño. Nunca lanza. */
    public Prueba probar() {
        String url = prop("spring.datasource.url", null);
        if (url == null || url.isBlank()) {
            return new Prueba(false, "No hay 'spring.datasource.url' configurada (revisa application.yaml).",
                    null, null, null, null, null);
        }
        if (MotorBd.desdeUrl(url) == MotorBd.SQLITE) return probarSqlite(url);

        String usuario = prop("spring.datasource.username", "");
        String password = prop("spring.datasource.password", "");

        Properties p = new Properties();
        if (usuario != null) p.setProperty("user", usuario);
        if (password != null) p.setProperty("password", password);
        p.setProperty("connectTimeout", String.valueOf(TIMEOUT_CONEXION_MS));
        p.setProperty("loginTimeout", "6");

        long t0 = System.currentTimeMillis();
        try (Connection c = MotorBd.desdeUrl(url).conectar(url, p)) {
            String version = null;
            String base = c.getCatalog();
            try (PreparedStatement ps = c.prepareStatement("SELECT VERSION()");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) version = rs.getString(1);
            }
            Long tablas = null;
            Double tamanoMb = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*), COALESCE(ROUND(SUM(data_length + index_length) / 1024 / 1024, 1), 0) "
                            + "FROM information_schema.tables WHERE table_schema = DATABASE()");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    tablas = rs.getLong(1);
                    tamanoMb = rs.getDouble(2);
                }
            }
            long ms = System.currentTimeMillis() - t0;
            return new Prueba(true, "Conexión correcta", version, base, tablas, tamanoMb, ms);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.warn("Prueba de conexión fallida: {}", e.getMessage());
            return new Prueba(false, mensajeConexion(e), null, null, null, null, ms);
        }
    }

    /** Prueba de conexión para SQLite: abre el archivo y cuenta sus tablas. */
    private Prueba probarSqlite(String url) {
        String base = ConfigBootstrapDb.nombreDeArchivo(ConfigBootstrapDb.archivoDeUrlSqlite(url));
        long t0 = System.currentTimeMillis();
        try (Connection c = MotorBd.SQLITE.conectar(url, new Properties())) {
            String version = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT sqlite_version()");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) version = rs.getString(1);
            }
            Long tablas = cuenta(c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");
            Double tamanoMb = null;
            try {
                Path archivo = Path.of(ConfigBootstrapDb.archivoDeUrlSqlite(url));
                if (Files.isRegularFile(archivo)) tamanoMb = Math.round(Files.size(archivo) / 1048576.0 * 10) / 10.0;
            } catch (Exception ignored) {
                // tamaño no disponible: se omite
            }
            long ms = System.currentTimeMillis() - t0;
            return new Prueba(true, "Conexión correcta", version, base, tablas, tamanoMb, ms);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            log.warn("Prueba de conexión SQLite fallida: {}", e.getMessage());
            return new Prueba(false, mensajeConexion(e), null, null, null, null, ms);
        }
    }

    /** true si la base de datos responde (distingue "primer arranque, sin base" de un error real). */
    public boolean disponible() {
        String url = prop("spring.datasource.url", null);
        if (url == null || url.isBlank()) return false;

        // SQLite: disponible sólo si el archivo existe y ya tiene el esquema aplicado.
        if (MotorBd.desdeUrl(url) == MotorBd.SQLITE) {
            String archivo = ConfigBootstrapDb.archivoDeUrlSqlite(url);
            if (archivo.isBlank() || !Files.isRegularFile(Path.of(archivo))) return false;
            try (Connection c = MotorBd.SQLITE.conectar(url, new Properties())) {
                Long n = cuenta(c,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='titulos'");
                return n != null && n > 0;
            } catch (Exception e) {
                return false;
            }
        }

        String usuario = prop("spring.datasource.username", "");
        String password = prop("spring.datasource.password", "");
        Properties p = new Properties();
        if (usuario != null) p.setProperty("user", usuario);
        if (password != null) p.setProperty("password", password);
        p.setProperty("connectTimeout", "3000");
        p.setProperty("loginTimeout", "3");
        try (Connection c = MotorBd.desdeUrl(url).conectar(url, p)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Inventario para la UI: conexión + resolución de rutas y binarios. */
    public Map<String, Object> inventario(ConfigRespaldoService cfgRespaldos) {
        return inventario(cfgRespaldos, true);
    }

    /**
     * Inventario para la UI. Con {@code hayBd=false} no se toca la base (sus
     * consultas fallarían con espera): se usan los valores por defecto para
     * resolver binarios. Así la pantalla de primer arranque responde rápido.
     */
    public Map<String, Object> inventario(ConfigRespaldoService cfgRespaldos, boolean hayBd) {
        Map<String, Object> out = new LinkedHashMap<>();
        Conexion c = conexion();
        MotorBd motor = MotorBd.desde(c.motor());
        out.put("motor", c.motor());
        out.put("motor_etiqueta", motor.etiqueta());
        out.put("es_servidor", motor.esServidor());
        out.put("host", c.host());
        out.put("puerto", c.puerto());
        out.put("base", c.base());
        out.put("usuario", c.usuario());
        out.put("driver", c.driver());
        out.put("url", c.url());
        out.put("raiz_app", cfgRespaldos.raizApp().toString());
        if (!motor.esServidor()) {
            // SQLite: no hay herramientas externas de volcado.
            out.put("archivo", ConfigBootstrapDb.archivoDeUrlSqlite(c.url()));
            out.put("bin_dump", null);
            out.put("bin_cliente", null);
            out.put("bin_dir", "");
        } else if (hayBd) {
            out.put("bin_dump", cfgRespaldos.binDump());
            out.put("bin_cliente", cfgRespaldos.binCliente());
            out.put("bin_dir", cfgRespaldos.cargar().binDir());
        } else {
            String binDir = cfgRespaldos.defecto().binDir();
            out.put("bin_dump", cfgRespaldos.binDumpEn(binDir));
            out.put("bin_cliente", cfgRespaldos.binClienteEn(binDir));
            out.put("bin_dir", binDir);
        }
        out.put("jdbc_disponible", jdbcDisponible());
        return out;
    }

    private Long cuenta(Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean jdbcDisponible() {
        String url = prop("spring.datasource.url", null);
        if (url == null) return false;
        try {
            return DriverManager.getDriver(url) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String mensajeConexion(Exception e) {
        String motor = MotorBd.desde(conexion().motor()).etiqueta();
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("Access denied")) {
            return "Acceso denegado: usuario o contraseña incorrectos (revisa la configuración de la conexión).";
        }
        if (m.contains("Public Key Retrieval is not allowed")) {
            return "El servidor MySQL exige una conexión cifrada para validar la contraseña.";
        }
        if (m.contains("Unknown database")) {
            return "La base de datos no existe. ¿Falta ejecutar database/schema.sql?";
        }
        if (m.contains("Connection refused") || m.contains("Could not connect")
                || m.contains("Communications link failure")) {
            return "El servidor no responde. ¿Está encendido " + motor + " y en el host/puerto correctos?";
        }
        return m;
    }

    private String prop(String clave, String defecto) {
        String v = env.getProperty(clave);
        return (v == null || v.isBlank()) ? defecto : v;
    }
}
