package api.crow.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura/escritura del archivo {@code config-db.yaml} (en el directorio de
 * trabajo, junto al jar). Contiene:
 *
 *  - {@code spring.datasource.*}: la conexión que usa la app.
 *  - {@code app.bootstrap-bd.*}: host, puerto, credenciales de ADMINISTRADOR y
 *    usuario/contraseña de la app, usados por el asistente de Configuración → Datos
 *    para probar la conexión admin y crear la base de datos.
 *
 * A propósito NO está cifrado: se necesita ANTES de que exista la base de datos y
 * antes de que la clave maestra esté disponible. Es el único sitio donde se guarda
 * la contraseña de administrador.
 */
@Slf4j
@Component
public class ConfigBootstrapDb {

    public static final String ARCHIVO = "config-db.yaml";

    /**
     * @param archivo ruta del archivo .db para motores de archivo (SQLite); vacío
     *                para los motores de servidor (MariaDB/MySQL).
     */
    public record Bootstrap(boolean enabled, String motor, String host, String puerto,
                            String usuarioAdmin, String passwordAdmin,
                            String nombreBase, String usuarioApp, String passwordApp,
                            String archivo) {

        /** Copia con el host/puerto/base/archivo indicados (mantiene motor, admin y usuario app). */
        public Bootstrap conDestino(String host, String puerto, String base, String archivo) {
            return new Bootstrap(enabled, motor, host, puerto, usuarioAdmin, passwordAdmin,
                    base, usuarioApp, passwordApp, archivo);
        }

        /** Motor de base de datos resuelto (nunca null; por defecto MariaDB). */
        public MotorBd motorEnum() {
            return MotorBd.desde(motor);
        }
    }

    private final Path archivo;

    public ConfigBootstrapDb() {
        this.archivo = resolver();
    }

    /**
     * Ubica config-db.yaml sin depender del directorio de trabajo: el launcher
     * arranca el jar desde la raíz del proyecto, pero el archivo vive junto al
     * backend (java_backend/). Se prueban varias ubicaciones y se usa la primera
     * que exista; si ninguna existe, se elige dónde crearla.
     */
    private static Path resolver() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        List<Path> cands = new ArrayList<>();
        cands.add(cwd.resolve(ARCHIVO));
        cands.add(cwd.resolve("java_backend").resolve(ARCHIVO));
        cands.add(cwd.resolve("..").resolve(ARCHIVO).normalize());
        Path raiz = buscarRaiz(cwd);
        if (raiz != null) {
            cands.add(raiz.resolve("java_backend").resolve(ARCHIVO));
            cands.add(raiz.resolve(ARCHIVO));
        }
        for (Path c : cands) {
            if (Files.isRegularFile(c)) return c;
        }
        Path jb = cwd.resolve("java_backend");
        return Files.isDirectory(jb) ? jb.resolve(ARCHIVO) : cwd.resolve(ARCHIVO);
    }

    /** Sube desde 'desde' buscando la carpeta que contiene java_backend/ (la raíz del repo). */
    private static Path buscarRaiz(Path desde) {
        Path dir = desde;
        for (int i = 0; dir != null && i < 6; i++) {
            if (Files.isDirectory(dir.resolve("java_backend"))) return dir;
            dir = dir.getParent();
        }
        return null;
    }

    /** Raíz de la app (donde vive java_backend/), robusta ante el cwd. */
    public static Path raizApp() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path r = buscarRaiz(cwd);
        return r != null ? r : cwd;
    }

    public Path archivo() {
        return archivo;
    }

    /** Carga el archivo; si no existe o está corrupto, devuelve valores por defecto. */
    public Bootstrap cargar() {
        if (!Files.isRegularFile(archivo)) return defecto();
        try (InputStream in = Files.newInputStream(archivo)) {
            return parse(new Yaml().load(in));
        } catch (Exception e) {
            log.warn("No se pudo leer {}: {}", archivo, e.getMessage());
            return defecto();
        }
    }

    /**
     * Valores por defecto para una instalación NUEVA (sin config-db.yaml): SQLite,
     * el motor sin servidor. El archivo .db se crea desde el asistente de Datos.
     */
    public Bootstrap defecto() {
        String archivo = raizApp().resolve("database").resolve("canal_videos_telegram.db").toString();
        return new Bootstrap(true, MotorBd.SQLITE.clave(), "", "", "", "", "canal_videos_telegram", "", "", archivo);
    }

    /** Escribe el archivo completo (datasource + bootstrap-bd). Lanza si no puede. */
    public void guardar(Bootstrap b) {
        MotorBd motor = b.motorEnum();
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("#  Conexión a la base de datos + credenciales de administrador\n");
        sb.append("# ============================================================\n");
        sb.append("#  Generado/actualizado desde la app (Configuración → Datos).\n");
        sb.append("#  NO está cifrado a propósito y NO debe subirse a git.\n");
        sb.append("# ============================================================\n\n");
        sb.append("spring:\n");
        sb.append("  datasource:\n");
        if (motor.esServidor()) {
            sb.append("    url: ").append(q(motor.prefijoUrl() + b.host() + ":" + b.puerto() + "/" + b.nombreBase()
                    + motor.parametrosUrl())).append("\n");
            sb.append("    username: ").append(q(b.usuarioApp())).append("\n");
            sb.append("    password: ").append(q(b.passwordApp())).append("\n");
        } else {
            sb.append("    url: ").append(q(motor.prefijoUrl() + b.archivo() + motor.parametrosUrl())).append("\n");
        }
        sb.append("    driver-class-name: ").append(motor.driver()).append("\n");
        if (!motor.esServidor()) {
            sb.append("    hikari:\n");
            sb.append("      # SQLite serializa las escrituras: un pool mínimo evita bloqueos.\n");
            sb.append("      maximum-pool-size: 1\n");
            sb.append("      minimum-idle: 0\n");
            sb.append("      connection-timeout: 1500\n");
            sb.append("      initialization-fail-timeout: -1\n");
        }
        sb.append("  jpa:\n");
        sb.append("    properties:\n");
        sb.append("      hibernate:\n");
        sb.append("        dialect: ").append(motor.dialecto()).append("\n\n");
        sb.append("app:\n");
        sb.append("  bootstrap-bd:\n");
        sb.append("    enabled: ").append(b.enabled()).append("\n");
        sb.append("    motor: ").append(motor.clave()).append("\n");
        if (motor.esServidor()) {
            sb.append("    host: ").append(q(b.host())).append("\n");
            sb.append("    puerto: ").append(numero(b.puerto())).append("\n");
            sb.append("    usuario-admin: ").append(q(b.usuarioAdmin())).append("\n");
            sb.append("    password-admin: ").append(q(b.passwordAdmin())).append("\n");
            sb.append("    nombre-base: ").append(q(b.nombreBase())).append("\n");
            sb.append("    usuario-app: ").append(q(b.usuarioApp())).append("\n");
            sb.append("    password-app: ").append(q(b.passwordApp())).append("\n");
        } else {
            sb.append("    archivo: ").append(q(b.archivo())).append("\n");
            sb.append("    nombre-base: ").append(q(b.nombreBase())).append("\n");
        }
        try {
            Files.writeString(archivo, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar " + archivo + ": " + e.getMessage());
        }
    }

    // ---- internos ----

    private Bootstrap parse(Object y) {
        Map<String, Object> root = mapa(y);
        Map<String, Object> b = mapa(mapa(root.get("app")).get("bootstrap-bd"));
        Map<String, Object> ds = mapa(mapa(root.get("spring")).get("datasource"));

        String url = txt(ds.get("url"));
        boolean enabled = b.get("enabled") == null || bool(b.get("enabled"));

        // Motor: del bloque bootstrap-bd si está; si no, derivado de la URL del datasource.
        String motorTxt = txt(b.get("motor"));
        MotorBd motor = motorTxt.isEmpty() ? MotorBd.desdeUrl(url) : MotorBd.desde(motorTxt);

        // Motores de archivo (SQLite): sólo interesan la ruta y el nombre.
        if (!motor.esServidor()) {
            String archivo = txt(b.get("archivo"));
            if (archivo.isEmpty()) archivo = archivoDeUrlSqlite(url);
            String base = txt(b.get("nombre-base"));
            if (base.isEmpty()) base = nombreDeArchivo(archivo);
            return new Bootstrap(enabled, motor.clave(), "", "", "", "", base, "", "", archivo);
        }

        String host = txt(b.get("host"));
        String puerto = txt(b.get("puerto"));
        String base = txt(b.get("nombre-base"));

        // Si el bloque bootstrap-bd no trae host/puerto/base, se derivan del datasource.
        if (url.startsWith("jdbc:") && (host.isEmpty() || base.isEmpty() || puerto.isEmpty())) {
            String resto = url.substring("jdbc:".length());
            int sep = resto.indexOf("://");
            if (sep > 0) {
                String cola = resto.substring(sep + 3);
                int barra = cola.indexOf('/');
                String autoridad = barra >= 0 ? cola.substring(0, barra) : cola;
                String b2 = barra >= 0 ? cola.substring(barra + 1) : "";
                int q = b2.indexOf('?');
                if (q >= 0) b2 = b2.substring(0, q);
                int dp = autoridad.indexOf(':');
                if (host.isEmpty()) host = dp >= 0 ? autoridad.substring(0, dp) : autoridad;
                if (puerto.isEmpty()) puerto = dp >= 0 ? autoridad.substring(dp + 1) : "3306";
                if (base.isEmpty()) base = b2;
            }
        }

        String usuarioApp = txt(b.get("usuario-app"));
        if (usuarioApp.isEmpty()) usuarioApp = txt(ds.get("username"));
        String passwordApp = txt(b.get("password-app"));
        if (passwordApp.isEmpty()) passwordApp = txt(ds.get("password"));

        return new Bootstrap(enabled,
                motor.clave(),
                host.isEmpty() ? "localhost" : host,
                puerto.isEmpty() ? motor.puertoDefectoTexto() : puerto,
                txt(b.get("usuario-admin")), txt(b.get("password-admin")),
                base, usuarioApp, passwordApp, "");
    }

    /** Extrae la ruta del archivo de una URL {@code jdbc:sqlite:<ruta>[?opciones]}. */
    public static String archivoDeUrlSqlite(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith(MotorBd.SQLITE.prefijoUrl())) {
            u = u.substring(MotorBd.SQLITE.prefijoUrl().length());
        }
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.trim();
    }

    /** Nombre de la base a partir de la ruta del archivo (sin carpeta ni extensión). */
    public static String nombreDeArchivo(String ruta) {
        if (ruta == null || ruta.isBlank()) return "";
        String n = ruta.replace('\\', '/');
        int barra = n.lastIndexOf('/');
        if (barra >= 0) n = n.substring(barra + 1);
        int punto = n.lastIndexOf('.');
        if (punto > 0) n = n.substring(0, punto);
        return n.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new LinkedHashMap<>();
    }

    private static String txt(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    /** Escalar YAML con comillas simples (dobla las comillas internas). */
    private static String q(String s) {
        String v = s == null ? "" : s;
        return "'" + v.replace("'", "''") + "'";
    }

    private static String numero(String s) {
        String v = s == null ? "" : s.trim();
        return v.matches("\\d+") ? v : q(v);
    }
}
