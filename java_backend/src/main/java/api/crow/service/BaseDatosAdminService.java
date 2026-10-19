package api.crow.service;

import api.crow.config.ConfigBootstrapDb;
import api.crow.config.ConfigBootstrapDb.Bootstrap;
import api.crow.config.MotorBd;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Operaciones de ADMINISTRADOR de la base de datos para el primer arranque:
 *
 *  - {@link #probarAdmin(Bootstrap)} conecta al servidor SIN base de datos con las
 *    credenciales indicadas y reporta versión, si la base existe y el nivel de
 *    permisos: si puede crear bases ({@code puedeCrearBase}, escenario A) y si puede
 *    crear tablas sobre la base indicada ({@code puedeCrearTablas}).
 *  - {@link #crear(Bootstrap)} (ESCENARIO A, credenciales root/admin) crea la base
 *    (CREATE DATABASE IF NOT EXISTS), ejecuta el DDL de database/schema.sql y crea el
 *    usuario dedicado de la app con sus permisos. Si ese usuario YA existe, se reutiliza
 *    SIN cambiarle la contraseña (solo se le da permiso sobre la base nueva).
 *  - {@link #crearTablas(Bootstrap)} (ESCENARIO B, usuario restringido) asume que la
 *    base YA existe y solo aplica el DDL sobre ella: no crea la base, ni el usuario,
 *    ni otorga permisos.
 *
 * Reglas de seguridad: NUNCA se emite DROP DATABASE ni DROP TABLE; si la base ya
 * existe, la creación se rechaza y no se toca nada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaseDatosAdminService {

    private final ConfigBootstrapDb cfgBootstrap;
    private final ConfigRespaldoService cfgRespaldos;

    public record PruebaAdmin(boolean ok, String mensaje, String version, boolean existeBase,
                              Long tablas, Boolean puedeCrearBase, Boolean puedeCrearTablas,
                              Boolean usuarioAppExiste) {}

    /** Estado del archivo y campos conocidos (sin contraseñas). */
    public Map<String, Object> resumen() {
        Bootstrap b = cfgBootstrap.cargar();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("archivo_config", cfgBootstrap.archivo().toString());
        m.put("existe_archivo", Files.isRegularFile(cfgBootstrap.archivo()));
        m.put("enabled", b.enabled());
        m.put("motor", b.motorEnum().clave());
        m.put("motor_etiqueta", b.motorEnum().etiqueta());
        m.put("es_servidor", b.motorEnum().esServidor());
        m.put("host", b.host());
        m.put("puerto", b.puerto());
        m.put("nombre_base", b.nombreBase());
        m.put("archivo", b.archivo());
        m.put("usuario_admin", b.usuarioAdmin());
        m.put("tiene_password_admin", !b.passwordAdmin().isEmpty());
        m.put("usuario_app", b.usuarioApp());
        m.put("tiene_password_app", !b.passwordApp().isEmpty());
        return m;
    }

    /** Prueba la conexión contra el servidor (sin base seleccionada) y detecta el escenario. */
    public PruebaAdmin probarAdmin(Bootstrap b) {
        if (!b.motorEnum().esServidor()) return probarAdminSqlite(b);
        if (esBlanco(b.usuarioAdmin())) {
            return new PruebaAdmin(false, "Indica el usuario de administrador (ej. root).",
                    null, false, null, null, null, null);
        }
        try (Connection c = abrirSinBase(b)) {
            String version = texto(c, "SELECT VERSION()");
            boolean existe = !esBlanco(b.nombreBase()) && existeBase(c, b.nombreBase());
            Long tablas = existe
                    ? largo(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?", b.nombreBase())
                    : null;
            Boolean puedeBase = puedeCrearBase(c);
            Boolean puedeTablas = esBlanco(b.nombreBase()) ? null : puedeCrearTablas(c, b.nombreBase());
            // ¿La cuenta de la app ya existe? (solo si es distinta de la de administración)
            Boolean appExiste = null;
            if (!esBlanco(b.usuarioApp()) && !b.usuarioApp().equalsIgnoreCase(b.usuarioAdmin())) {
                appExiste = usuarioExiste(c, b.usuarioApp());
            }
            return new PruebaAdmin(true, mensajePrueba(b, existe, tablas, puedeBase, puedeTablas, appExiste),
                    version, existe, tablas, puedeBase, puedeTablas, appExiste);
        } catch (Exception e) {
            return new PruebaAdmin(false, mensajeAdmin(e, b), null, false, null, null, null, null);
        }
    }

    /**
     * SQLite no tiene servidor ni credenciales: se comprueba la ruta del archivo.
     * Si el archivo ya existe con tablas, no se tocará; si no existe, se puede crear.
     */
    private PruebaAdmin probarAdminSqlite(Bootstrap b) {
        String archivo = b.archivo();
        if (esBlanco(archivo)) {
            return new PruebaAdmin(false, "Indica dónde guardar el archivo de la base de datos.",
                    null, false, null, null, null, null);
        }
        boolean tieneTablas = tieneTablasSqlite(archivo);
        boolean existeArchivo = Files.isRegularFile(Path.of(archivo));
        String version = existeArchivo ? versionSqlite(archivo) : null;
        Long tablas = tieneTablas ? contarTablasSqlite(archivo) : null;
        String mensaje;
        if (tieneTablas) {
            mensaje = "La base de datos ya existe" + tablasTxt(tablas) + " en: " + archivo
                    + ". No se tocará. Para empezar de cero, elige otra ubicación o borra ese archivo.";
        } else if (existeArchivo) {
            mensaje = "El archivo existe pero aún no tiene la base de datos. Se crearán las tablas en: " + archivo;
        } else {
            mensaje = "Todo listo para crear la base de datos en: " + archivo;
        }
        return new PruebaAdmin(true, mensaje, version, tieneTablas, tablas, true, true, null);
    }

    /** Redacta el diagnóstico según el escenario (A: puede crear base; B: usuario restringido). */
    private String mensajePrueba(Bootstrap b, boolean existe, Long tablas, Boolean puedeBase,
                                 Boolean puedeTablas, Boolean appExiste) {
        String notaApp = Boolean.TRUE.equals(appExiste)
                ? " Ojo: el usuario de la app '" + b.usuarioApp() + "' ya existe; se reutilizará sin cambiarle la contraseña."
                : "";
        if (esBlanco(b.nombreBase())) {
            return "Conexión correcta. Indica el nombre de la base de datos." + notaApp;
        }
        if (Boolean.TRUE.equals(puedeBase)) {
            if (existe) {
                return "Credenciales de administrador correctas. La base '" + b.nombreBase() + "' ya existe"
                        + tablasTxt(tablas) + "; no se tocará. Para crearla de cero, usa otro nombre." + notaApp;
            }
            return "Credenciales de administrador correctas. Puedes crear la base '" + b.nombreBase()
                    + "' y sus tablas automáticamente." + notaApp;
        }
        if (existe) {
            if (Boolean.TRUE.equals(puedeTablas)) {
                return "Conexión correcta (usuario con permisos sobre la base). La base ya existe"
                        + tablasTxt(tablas) + "; puedes crear las tablas que falten." + notaApp;
            }
            return "Conexión correcta, pero este usuario no puede crear tablas en '" + b.nombreBase()
                    + "'. Pide al administrador que le otorgue el permiso CREATE sobre esa base." + notaApp;
        }
        return "Conexión correcta (usuario sin permiso para crear bases). No es obligatorio usar root: "
                + "crea la base y otorga permisos a tu usuario, y luego pulsa 'Crear las tablas'." + notaApp;
    }

    private static String tablasTxt(Long tablas) {
        return tablas == null ? "" : " (" + tablas + " tablas)";
    }

    /** true si la base indicada ya existe (o si no se puede comprobar). */
    public boolean existeBase(Bootstrap b) {
        if (!b.motorEnum().esServidor()) return tieneTablasSqlite(b.archivo());
        if (esBlanco(b.nombreBase())) return false;
        try (Connection c = abrirSinBase(b)) {
            return existeBase(c, b.nombreBase());
        } catch (Exception e) {
            return false;
        }
    }

    /** Crea la base, aplica el esquema y el usuario de la app. Persiste la conexión. */
    public Map<String, Object> crear(Bootstrap b) {
        if (!b.motorEnum().esServidor()) return crearSqlite(b, false);
        validar(b);
        String ddl = leerEsquema(b);
        try (Connection c = abrirSinBase(b)) {
            if (existeBase(c, b.nombreBase())) {
                throw new IllegalArgumentException("La base de datos '" + b.nombreBase()
                        + "' ya existe; no se toca nada. Para usarla, cámbiale el nombre o edita la conexión.");
            }
            ejecutar(c, "CREATE DATABASE IF NOT EXISTS `" + b.nombreBase()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            ejecutar(c, "USE `" + b.nombreBase() + "`");

            int tablas = aplicarEsquema(c, ddl);

            String uApp = b.usuarioApp();
            if (uApp.equalsIgnoreCase(b.usuarioAdmin())) {
                // Mismo usuario que administración: no se toca su contraseña; solo se le da permiso.
                if (!b.passwordApp().equals(b.passwordAdmin())) {
                    throw new IllegalArgumentException("Usaste el mismo nombre para el administrador y para la app,"
                            + " pero con contraseñas distintas. Usa la misma contraseña en ambos campos, o elige"
                            + " otro nombre para el usuario de la aplicación.");
                }
            } else if (Boolean.TRUE.equals(usuarioExiste(c, uApp))) {
                // Ya existe: se reutiliza SIN cambiarle la contraseña. La escrita debe ser la suya.
                if (!puedeConectar(uApp, b.passwordApp(), b)) {
                    throw new IllegalArgumentException("El usuario '" + uApp + "' ya existe y la contraseña que"
                            + " escribiste no coincide. Escribe su contraseña actual, o cambia el nombre del"
                            + " usuario de la aplicación.");
                }
            } else {
                ejecutar(c, "CREATE USER IF NOT EXISTS '" + uApp + "'@'localhost' IDENTIFIED BY '"
                        + esc(b.passwordApp()) + "'");
            }
            ejecutar(c, "GRANT ALL PRIVILEGES ON `" + b.nombreBase() + "`.* TO '" + uApp + "'@'localhost'");
            ejecutar(c, "FLUSH PRIVILEGES");

            // Deja escrita la conexión para que al reiniciar la app use esta base.
            cfgBootstrap.guardar(new Bootstrap(true, b.motor(), b.host(), b.puerto(), b.usuarioAdmin(),
                    b.passwordAdmin(), b.nombreBase(), b.usuarioApp(), b.passwordApp(), b.archivo()));

            log.info("Base de datos '{}' creada con {} tablas y usuario '{}'@'localhost'.",
                    b.nombreBase(), tablas, b.usuarioApp());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("base", b.nombreBase());
            out.put("tablas", tablas);
            out.put("host", b.host());
            out.put("puerto", b.puerto());
            out.put("usuario_app", b.usuarioApp());
            out.put("mensaje", "Base de datos creada. Reinicia la aplicación para conectarte a ella.");
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la base: " + mensajeAdmin(e, b));
        }
    }

    /**
     * ESCENARIO B: la base YA existe y el usuario solo tiene permisos sobre ella.
     * Aplica únicamente el DDL de tablas (todas CREATE TABLE IF NOT EXISTS): no crea
     * la base, ni el usuario, ni otorga permisos. Persiste la conexión de la app.
     */
    public Map<String, Object> crearTablas(Bootstrap b) {
        if (!b.motorEnum().esServidor()) return crearSqlite(b, true);
        validarBase(b);
        String ddl = leerEsquema(b);
        try (Connection c = abrirConBase(b)) {
            if (Boolean.FALSE.equals(puedeCrearTablas(c, b.nombreBase()))) {
                throw new IllegalStateException("El usuario '" + b.usuarioAdmin() + "' no tiene permiso CREATE "
                        + "sobre la base '" + b.nombreBase() + "'. Pide al administrador que lo otorgue.");
            }
            int tablas = aplicarEsquema(c, ddl);

            // La app se conecta con el usuario dedicado si se indicó; si no, con el mismo.
            boolean dedicado = !esBlanco(b.usuarioApp());
            String uApp = dedicado ? b.usuarioApp() : b.usuarioAdmin();
            String pApp = dedicado ? b.passwordApp() : b.passwordAdmin();
            cfgBootstrap.guardar(new Bootstrap(true, b.motor(), b.host(), b.puerto(), b.usuarioAdmin(),
                    b.passwordAdmin(), b.nombreBase(), uApp, pApp, b.archivo()));

            log.info("Tablas aseguradas en la base existente '{}' ({} sentencias) por '{}'.",
                    b.nombreBase(), tablas, b.usuarioAdmin());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("base", b.nombreBase());
            out.put("tablas", tablas);
            out.put("host", b.host());
            out.put("puerto", b.puerto());
            out.put("usuario_app", uApp);
            out.put("mensaje", "Tablas creadas en la base existente. Reinicia la aplicación para conectarte a ella.");
            return out;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron crear las tablas: " + mensajeAdmin(e, b));
        }
    }

    /**
     * Crea (o asegura) la base SQLite: abre/crea el archivo y aplica el esquema.
     * No hay usuarios ni permisos. Si el archivo ya tiene la base y
     * {@code permitirExistente} es false, se rechaza para no pisar datos.
     */
    private Map<String, Object> crearSqlite(Bootstrap b, boolean permitirExistente) {
        String archivo = b.archivo();
        if (esBlanco(archivo)) {
            throw new IllegalArgumentException("Indica dónde guardar el archivo de la base de datos.");
        }
        Path p = Path.of(archivo).toAbsolutePath().normalize();
        if (Files.isRegularFile(p) && tieneTablasSqlite(archivo) && !permitirExistente) {
            throw new IllegalArgumentException("La base de datos ya existe (" + p + "). No se toca nada. "
                    + "Elige otra ubicación o borra ese archivo para empezar de cero.");
        }
        String base = esBlanco(b.nombreBase()) ? ConfigBootstrapDb.nombreDeArchivo(archivo) : b.nombreBase();
        String ddl = leerEsquema(b);
        try (Connection c = abrirSqlite(archivo)) {
            aplicarEsquema(c, ddl);
            long tablas = cuenta(c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");

            cfgBootstrap.guardar(new Bootstrap(true, b.motor(), "", "", "", "", base, "", "", p.toString()));

            log.info("Base SQLite '{}' lista ({} tablas).", p, tablas);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("base", base);
            out.put("tablas", tablas);
            out.put("host", "");
            out.put("puerto", "");
            out.put("usuario_app", "");
            out.put("mensaje", "Base de datos creada. Reinicia la aplicación para conectarte a ella.");
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la base SQLite: " + e.getMessage());
        }
    }

    /** Cantidad de tablas en la base indicada (0 si no existe o no se puede consultar). */
    public long contarTablas(Bootstrap b) {
        if (!b.motorEnum().esServidor()) {
            Long n = contarTablasSqlite(b.archivo());
            return n == null ? 0 : n;
        }
        if (esBlanco(b.nombreBase())) return 0;
        try (Connection c = abrirSinBase(b)) {
            Long n = largo(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?", b.nombreBase());
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- internos ----

    private void validar(Bootstrap b) {
        validarBase(b);
        if (!b.motorEnum().esServidor()) return;   // SQLite: sin usuario/contraseña de app
        if (esBlanco(b.usuarioApp()) || esBlanco(b.passwordApp())) {
            throw new IllegalArgumentException("Indica el usuario y la contraseña que usará la aplicación.");
        }
        if (!b.usuarioApp().matches("[A-Za-z0-9_.\\-]{1,64}")) {
            throw new IllegalArgumentException("El usuario de la app solo admite letras, números, punto, guion y _ (máx. 64).");
        }
    }

    /** Validaciones comunes (escenario A y B). */
    private void validarBase(Bootstrap b) {
        if (!b.motorEnum().esServidor()) {
            if (esBlanco(b.archivo())) {
                throw new IllegalArgumentException("Indica dónde guardar el archivo de la base de datos.");
            }
            return;
        }
        if (esBlanco(b.nombreBase()) || !b.nombreBase().matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("El nombre de la base solo admite letras, números y _ (máx. 64).");
        }
        if (esBlanco(b.usuarioAdmin())) {
            throw new IllegalArgumentException("Indica el usuario que se conectará a la base (ej. root).");
        }
        if (esBlanco(b.host()) || !b.puerto().matches("\\d{1,5}")) {
            throw new IllegalArgumentException("Revisa el host y el puerto del servidor de base de datos.");
        }
    }

    private Connection abrirSinBase(Bootstrap b) throws SQLException {
        return abrir(b, "");
    }

    private Connection abrirConBase(Bootstrap b) throws SQLException {
        return abrir(b, b.nombreBase());
    }

    private Connection abrir(Bootstrap b, String base) throws SQLException {
        if (!b.motorEnum().esServidor()) return abrirSqlite(b.archivo());
        String url = b.motorEnum().prefijoUrl() + b.host() + ":" + b.puerto() + "/" + (base == null ? "" : base);
        Properties p = new Properties();
        p.setProperty("user", b.usuarioAdmin());
        p.setProperty("password", b.passwordAdmin() == null ? "" : b.passwordAdmin());
        p.setProperty("connectTimeout", "6000");
        p.setProperty("loginTimeout", "6");
        return b.motorEnum().conectar(url, p);
    }

    /** Abre (y crea si hace falta, junto con su carpeta) el archivo de SQLite. */
    private Connection abrirSqlite(String archivo) throws SQLException {
        if (esBlanco(archivo)) throw new SQLException("Falta la ruta del archivo de SQLite.");
        Path p = Path.of(archivo).toAbsolutePath().normalize();
        try {
            Path dir = p.getParent();
            if (dir != null) Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SQLException("No se pudo crear la carpeta de la base de datos: " + e.getMessage(), e);
        }
        String url = MotorBd.SQLITE.prefijoUrl() + p + MotorBd.SQLITE.parametrosUrl();
        return MotorBd.SQLITE.conectar(url, new Properties());
    }

    /** true si el archivo SQLite existe y ya tiene tablas propias (no internas). */
    private boolean tieneTablasSqlite(String archivo) {
        if (esBlanco(archivo) || !Files.isRegularFile(Path.of(archivo))) return false;
        try (Connection c = abrirSqlite(archivo)) {
            return cuenta(c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'") > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Long contarTablasSqlite(String archivo) {
        try (Connection c = abrirSqlite(archivo)) {
            return cuenta(c, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");
        } catch (Exception e) {
            return null;
        }
    }

    private String versionSqlite(String archivo) {
        try (Connection c = abrirSqlite(archivo)) {
            return texto(c, "SELECT sqlite_version()");
        } catch (Exception e) {
            return null;
        }
    }

    private long cuenta(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private boolean existeBase(Connection c, String base) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            ps.setString(1, base);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** ¿Existe la cuenta 'usuario'@'localhost'? null si no se puede comprobar (sin permiso). */
    private Boolean usuarioExiste(Connection c, String usuario) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM mysql.user WHERE User = ? AND Host = 'localhost'")) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** ¿Las credenciales dadas sirven para conectar al servidor? (para validar un usuario que ya existe). */
    private boolean puedeConectar(String usuario, String password, Bootstrap b) {
        String url = b.motorEnum().prefijoUrl() + b.host() + ":" + b.puerto() + "/";
        Properties p = new Properties();
        p.setProperty("user", usuario);
        p.setProperty("password", password == null ? "" : password);
        p.setProperty("connectTimeout", "6000");
        p.setProperty("loginTimeout", "6");
        try (Connection ignored = b.motorEnum().conectar(url, p)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void ejecutar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String texto(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private Long largo(Connection c, String sql, String param) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** ¿El usuario conectado tiene permiso global para CREATE DATABASE (escenario A)? */
    private Boolean puedeCrearBase(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
            while (rs.next()) {
                String g = rs.getString(1);
                if (g == null) continue;
                String u = g.toUpperCase();
                if (u.contains("ALL PRIVILEGES ON *.*")) return true;
                if (u.contains("CREATE") && u.matches(".*ON \\*\\.\\*.*")) return true;
            }
            return false;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ¿El usuario conectado puede crear tablas en la base dada? Se cumple si tiene
     * privilegios globales (ON *.*) o un GRANT con CREATE/ALL sobre esa base.
     * Nota: MariaDB escapa los guiones bajos en SHOW GRANTS (ej. `mi\_base`), así
     * que se eliminan las barras invertidas antes de comparar.
     */
    private Boolean puedeCrearTablas(Connection c, String base) {
        String b = base.replace("`", "").toUpperCase();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
            while (rs.next()) {
                String g = rs.getString(1);
                if (g == null) continue;
                String u = g.toUpperCase().replace("\\", "");
                if (u.contains("ALL PRIVILEGES ON *.*")) return true;
                if (u.contains("CREATE") && u.matches(".*ON \\*\\.\\*.*")) return true;
                if ((u.contains("ON `" + b + "`.*") || u.contains("ON " + b + ".*"))
                        && (u.contains("ALL PRIVILEGES") || u.contains("CREATE"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return null;
        }
    }

    /** Ejecuta el DDL de tablas (sentencias ya filtradas). Devuelve cuántas aplicó. */
    private int aplicarEsquema(Connection c, String ddl) throws SQLException {
        int n = 0;
        for (String st : sentencias(ddl)) {
            ejecutar(c, st);
            n++;
        }
        return n;
    }

    /** Lee el DDL canónico (schema.sql o schema-sqlite.sql) desde la raíz del proyecto. */
    private String leerEsquema(Bootstrap b) {
        String nombre = b.motorEnum().esServidor() ? "schema.sql" : "schema-sqlite.sql";
        Path sql = cfgRespaldos.raizApp().resolve("database").resolve(nombre);
        if (!Files.isRegularFile(sql)) {
            throw new IllegalStateException("No se encontró el esquema: " + sql
                    + ". Debe existir database/" + nombre + " en la raíz del proyecto.");
        }
        try {
            return Files.readString(sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + sql + ": " + e.getMessage());
        }
    }

    /** Separa el DDL en sentencias, descartando CREATE DATABASE y USE (los pone la app). */
    static List<String> sentencias(String ddl) {
        StringBuilder limpio = new StringBuilder();
        for (String linea : ddl.split("\\r?\\n")) {
            int com = linea.indexOf("--");
            limpio.append(com >= 0 ? linea.substring(0, com) : linea).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String s : limpio.toString().split(";")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            String u = t.toUpperCase();
            if (u.startsWith("CREATE DATABASE") || u.startsWith("USE ")) continue;
            out.add(t);
        }
        return out;
    }

    private String mensajeAdmin(Exception e, Bootstrap b) {
        String motor = b.motorEnum().etiqueta();
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("Access denied") && m.contains("database")) {
            return "Acceso denegado: el usuario no tiene permisos sobre esa base de datos.";
        }
        if (m.contains("Access denied")) return "Acceso denegado: usuario o contraseña incorrectos.";
        if (m.contains("command denied")) {
            return "El usuario no tiene permiso para crear tablas en esa base. "
                    + "Pide al administrador que le otorgue el permiso CREATE.";
        }
        if (m.contains("Public Key Retrieval is not allowed")) {
            return "El servidor MySQL exige una conexión cifrada para validar la contraseña. "
                    + "Activa SSL en el servidor o permite la descarga de la clave pública.";
        }
        if (m.contains("Unknown database")) {
            return "La base de datos indicada no existe. Créala primero o usa credenciales de administrador.";
        }
        if (m.contains("Connection refused") || m.contains("Could not connect")
                || m.contains("Communications link failure")) {
            return "El servidor no responde. ¿Está encendido " + motor + " y en el host/puerto correctos?";
        }
        if (m.contains("Unknown host")) return "No se encontró el host indicado.";
        if (m.contains("auth_gssapi_client") || m.contains("Unable to load authentication plugin")) {
            return "El servidor en ese host/puerto no parece ser " + motor
                    + ". Comprueba que elegiste el motor correcto arriba (MariaDB o MySQL).";
        }
        return m;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static boolean esBlanco(String s) {
        return s == null || s.isBlank();
    }
}
