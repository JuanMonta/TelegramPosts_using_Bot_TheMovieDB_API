package api.crow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    /** Datos de conexión vigentes, sin secretos. */
    public Conexion conexion() {
        String url = prop("spring.datasource.url", "");
        String usuario = prop("spring.datasource.username", "");
        String driver = prop("spring.datasource.driver-class-name", "");
        String motor = "mariadb";
        String host = "";
        String puerto = "";
        String base = "";

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
        String usuario = prop("spring.datasource.username", "");
        String password = prop("spring.datasource.password", "");

        Properties p = new Properties();
        if (usuario != null) p.setProperty("user", usuario);
        if (password != null) p.setProperty("password", password);
        p.setProperty("connectTimeout", String.valueOf(TIMEOUT_CONEXION_MS));
        p.setProperty("loginTimeout", "6");

        long t0 = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(url, p)) {
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

    /** true si la base de datos responde (distingue "primer arranque, sin base" de un error real). */
    public boolean disponible() {
        String url = prop("spring.datasource.url", null);
        if (url == null || url.isBlank()) return false;
        String usuario = prop("spring.datasource.username", "");
        String password = prop("spring.datasource.password", "");
        Properties p = new Properties();
        if (usuario != null) p.setProperty("user", usuario);
        if (password != null) p.setProperty("password", password);
        p.setProperty("connectTimeout", "3000");
        p.setProperty("loginTimeout", "3");
        try (Connection c = DriverManager.getConnection(url, p)) {
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
        out.put("motor", c.motor());
        out.put("host", c.host());
        out.put("puerto", c.puerto());
        out.put("base", c.base());
        out.put("usuario", c.usuario());
        out.put("driver", c.driver());
        out.put("url", c.url());
        out.put("raiz_app", cfgRespaldos.raizApp().toString());
        if (hayBd) {
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
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("Access denied")) {
            return "Acceso denegado: usuario o contraseña incorrectos (revisa application-local.yaml).";
        }
        if (m.contains("Unknown database")) {
            return "La base de datos no existe. ¿Falta ejecutar database/schema.sql?";
        }
        if (m.contains("Connection refused") || m.contains("Could not connect")) {
            return "El servidor no responde. ¿Está encendido MariaDB y en el host/puerto correctos?";
        }
        return m;
    }

    private String prop(String clave, String defecto) {
        String v = env.getProperty(clave);
        return (v == null || v.isBlank()) ? defecto : v;
    }
}
