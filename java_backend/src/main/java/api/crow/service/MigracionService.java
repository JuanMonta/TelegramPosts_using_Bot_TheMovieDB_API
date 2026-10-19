package api.crow.service;

import api.crow.config.ConfigBootstrapDb;
import api.crow.config.ConfigBootstrapDb.Bootstrap;
import api.crow.config.MotorBd;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Migración asistida de datos entre motores de base de datos.
 *
 * Lee la base ACTIVA (origen) y vuelca su contenido en otra base (destino) del
 * motor elegido, adaptando el esquema y los tipos. Sirve para cualquier
 * combinación (MariaDB, MySQL y SQLite en los dos sentidos).
 *
 * Garantías de seguridad:
 *  - Exige una confirmación (el nombre exacto de la base actual) antes de tocar nada.
 *  - Nunca borra la base de origen: solo lee de ella.
 *  - Escribe SIEMPRE en un destino distinto del origen y rechaza destino igual al origen.
 *  - Si el destino ya tiene datos, se niega salvo que se pida vaciarlo explícitamente.
 *  - Copia y luego VERIFICA (nº de filas + suma de comprobación por tabla). Solo si
 *    TODO cuadra se reescribe config-db.yaml; si algo falla, no se cambia el motor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigracionService {

    /** Orden de copia con los "padres" primero (aunque también se desactivan las FK). */
    private static final List<String> ORDEN_TABLAS = List.of(
            "titulos", "bots", "acoplamientos", "canales", "links_video",
            "publicaciones", "cola_publicaciones", "comentarios", "acoplamiento_canales",
            "caption_templates", "settings", "sugerencias",
            "config_app", "config_respaldo", "respaldos");

    private final BaseDatosService db;
    private final ConfigBootstrapDb cfgBootstrap;
    private final ConfigRespaldoService cfgRespaldos;
    private final RespaldoService respaldos;

    public record Columna(String nombre, int tipo) {
        boolean binaria() {
            return tipo == Types.BINARY || tipo == Types.VARBINARY || tipo == Types.LONGVARBINARY
                    || tipo == Types.BLOB;
        }

        boolean fecha() {
            return tipo == Types.DATE || tipo == Types.TIME || tipo == Types.TIMESTAMP
                    || tipo == Types.TIMESTAMP_WITH_TIMEZONE || tipo == Types.TIME_WITH_TIMEZONE;
        }
    }

    private record ConexionDestino(Connection c, boolean recienCreada) {}

    // ============================================================
    //  Análisis previo (sin tocar nada)
    // ============================================================

    /** Examina origen y destino y describe lo que se migraría. No modifica nada. */
    public Map<String, Object> analizar(Bootstrap destino) {
        MotorBd mOt = db.motor();
        MotorBd mDe = destino.motorEnum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origen", resumenMotor(mOt, db.conexion().base(), db.esArchivo()
                ? db.archivoSqlite() : null));
        out.put("destino", resumenMotor(mDe, destino.nombreBase(), destino.archivo()));
        out.put("destino_existe", false);
        out.put("destino_tiene_datos", false);
        List<String> avisos = new ArrayList<>();

        // --- origen: tablas y filas ---
        List<Map<String, Object>> tablas = new ArrayList<>();
        long total = 0;
        try (Connection src = db.nuevaConexion()) {
            List<String> presentes = tablasPresentes(src, mOt);
            for (String t : ORDEN_TABLAS) {
                if (!presentes.contains(t)) continue;
                long n = contar(src, t);
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("tabla", t);
                fila.put("filas", n);
                tablas.add(fila);
                total += n;
            }
        } catch (SQLException e) {
            out.put("ok", false);
            out.put("mensaje", "No se pudo leer la base de datos actual: " + e.getMessage());
            return out;
        }
        out.put("tablas", tablas);
        out.put("total_filas", total);

        // --- destino: ¿existe? ¿tiene datos? (sin crearlo) ---
        try {
            boolean existe = false;
            long filas = 0;
            List<String> tDes = new ArrayList<>();
            if (mDe.esServidor()) {
                existe = existeBaseEnServidor(destino);
                if (existe) {
                    try (Connection conBase = abrirDestino(destino, true)) {
                        tDes = tablasPresentes(conBase, mDe);
                        for (String t : ORDEN_TABLAS) if (tDes.contains(t)) filas += contar(conBase, t);
                    }
                }
            } else {
                String arch = destino.archivo();
                if (!blanco(arch) && Files.isRegularFile(Path.of(arch)) && tieneTablas(Path.of(arch), mDe)) {
                    existe = true;
                    try (Connection dc = abrirDestino(destino, true)) {
                        tDes = tablasPresentes(dc, mDe);
                        for (String t : ORDEN_TABLAS) if (tDes.contains(t)) filas += contar(dc, t);
                    }
                }
            }
            out.put("destino_existe", existe);
            out.put("destino_tiene_datos", filas > 0);
            out.put("destino_tablas", tDes);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("mensaje", "No se pudo comprobar el destino: " + mensaje(e));
            return out;
        }

        if (mismoOrigen(mOt, destino)) {
            avisos.add("El destino es la MISMA base de datos que el origen. Elige otra base o archivo.");
        }
        if (Boolean.TRUE.equals(out.get("destino_tiene_datos"))) {
            avisos.add("El destino ya contiene datos. Para no sobrescribirlos, elige una base vacía "
                    + "o marca la casilla para vaciarlo (se borrarán sus tablas de la aplicación).");
        }
        if (mDe.esServidor()) {
            boolean appOk = !blanco(destino.usuarioApp()) && !blanco(destino.passwordApp());
            if (!appOk) {
                avisos.add("El motor de destino es de servidor: conviene indicar el usuario y la contraseña "
                        + "de la aplicación (o se reutilizarán las credenciales de administrador).");
            }
        }
        avisos.add("Origen y destino deben usar la MISMA versión del esquema. Si el destino muestra avisos "
                + "sobre tablas o columnas que faltan, actualiza el archivo de esquema antes de migrar.");
        out.put("avisos", avisos);
        out.put("ok", true);
        out.put("listo", !mismoOrigen(mOt, destino));
        return out;
    }

    // ============================================================
    //  Migración real
    // ============================================================

    public Map<String, Object> migrar(Bootstrap destino, boolean vaciarDestino, String confirmacion) {
        MotorBd mOt = db.motor();
        MotorBd mDe = destino.motorEnum();

        String esperado = db.conexion().base();
        if (confirmacion == null || !confirmacion.trim().equalsIgnoreCase(esperado)) {
            throw new IllegalArgumentException("Operación cancelada: para migrar escribe el nombre exacto de la "
                    + "base de datos actual (" + esperado + ").");
        }
        if (mismoOrigen(mOt, destino)) {
            throw new IllegalArgumentException("El destino es la MISMA base de datos que el origen. Elige otra base "
                    + "o archivo para migrar.");
        }

        respaldos.reservar("migracion");
        try {
            return ejecutarMigracion(destino, vaciarDestino, mOt, mDe);
        } finally {
            respaldos.liberar();
        }
    }

    private Map<String, Object> ejecutarMigracion(Bootstrap destino, boolean vaciar, MotorBd mOt, MotorBd mDe) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> resultados = new ArrayList<>();
        List<String> errores = new ArrayList<>();
        out.put("tablas", resultados);
        out.put("errores", errores);
        out.put("reiniciar", false);

        Connection dst = null;
        try (Connection src = db.nuevaConexion()) {
            // 1) Leer origen (tablas y columnas).
            Map<String, List<Columna>> esquemaOrigen = new LinkedHashMap<>();
            List<String> presentes = tablasPresentes(src, mOt);
            for (String t : ORDEN_TABLAS) {
                if (presentes.contains(t)) esquemaOrigen.put(t, columnasDe(src, mOt, t));
            }

            // 2) Preparar destino: crear/conectar, comprobar vacío, aplicar esquema.
            ConexionDestino cd = prepararDestino(destino, mDe, vaciar);
            dst = cd.c();

            // 3) Comprobar que el destino tenga todas las tablas/columnas del origen.
            List<String> tDes = tablasPresentes(dst, mDe);
            for (Map.Entry<String, List<Columna>> e : esquemaOrigen.entrySet()) {
                String t = e.getKey();
                if (!tDes.contains(t)) {
                    throw new IllegalStateException("El destino no tiene la tabla '" + t
                            + "'. Actualiza database/" + (mDe.esServidor() ? "schema.sql" : "schema-sqlite.sql")
                            + " antes de migrar.");
                }
                List<String> cDes = columnasDe(dst, mDe, t).stream().map(Columna::nombre).toList();
                for (Columna c : e.getValue()) {
                    if (!cDes.contains(c.nombre())) {
                        throw new IllegalStateException("El destino no tiene la columna '" + t + "." + c.nombre()
                                + "'. Actualiza el archivo de esquema antes de migrar.");
                    }
                }
            }

            // 4) Copiar con las claves foráneas desactivadas (el orden deja de importar).
            fk(dst, mDe, false);
            try {
                for (Map.Entry<String, List<Columna>> e : esquemaOrigen.entrySet()) {
                    String t = e.getKey();
                    long[] res = copiarTabla(src, mOt, dst, mDe, t, e.getValue());
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("tabla", t);
                    fila.put("filas_origen", res[0]);
                    fila.put("checksum_origen", hex(res[1]));
                    resultados.add(fila);
                }
            } finally {
                fk(dst, mDe, true);
            }

            // 5) Verificar releyendo el destino (conteo + checksum por tabla).
            for (Map<String, Object> fila : resultados) {
                String t = (String) fila.get("tabla");
                List<Columna> cols = esquemaOrigen.get(t);
                long[] des = checksumTabla(dst, mDe, t, cols);
                long nDes = des[0];
                long sumDes = des[1];
                long nOri = (Long) fila.get("filas_origen");
                long sumOri = Long.parseUnsignedLong((String) fila.get("checksum_origen"), 16);
                boolean ok = nOri == nDes && sumOri == sumDes;
                fila.put("filas_destino", nDes);
                fila.put("checksum_destino", hex(sumDes));
                fila.put("ok", ok);
                if (!ok) {
                    errores.add("La tabla '" + t + "' no cuadra: origen " + nOri + " fila(s) / destino " + nDes
                            + " fila(s).");
                }
            }
        } catch (Exception e) {
            log.error("Migración falló: {}", e.getMessage(), e);
            out.put("ok", false);
            out.put("correcto", false);
            out.put("mensaje", "La migración no se completó: " + mensaje(e)
                    + " No se cambió el motor y tus datos siguen intactos en " + mOt.etiqueta() + ".");
            return out;
        } finally {
            cerrar(dst);
        }

        boolean correcto = errores.isEmpty();
        out.put("ok", correcto);
        out.put("correcto", correcto);
        if (!correcto) {
            out.put("mensaje", "La verificación encontró diferencias. NO se cambió el motor; revisa el detalle. "
                    + "Para volver a intentarlo, vacía el destino y repite.");
            return out;
        }

        // 6) Todo correcto: persistir la nueva conexión (se aplica al reiniciar).
        String nombreBase = mDe.esServidor()
                ? destino.nombreBase()
                : ConfigBootstrapDb.nombreDeArchivo(destino.archivo());
        cfgBootstrap.guardar(new Bootstrap(true, mDe.clave(), destino.host(), destino.puerto(),
                destino.usuarioAdmin(), destino.passwordAdmin(), nombreBase,
                credencialAppUsuario(destino), credencialAppPassword(destino), destino.archivo()));
        out.put("reiniciar", true);
        out.put("mensaje", "Migración completada y verificada. Los datos quedaron en " + mDe.etiqueta()
                + ". Reinicia la aplicación para usar el nuevo motor.");
        log.info("Migración {} -> {} completada y verificada ({} tabla(s)).",
                mOt.clave(), mDe.clave(), resultados.size());
        return out;
    }

    /**
     * Asegura que el destino existe con el esquema aplicado y (si se pidió) vacío.
     *  - Motor de archivo: crea el .db si falta y aplica schema-sqlite.sql.
     *  - Motor de servidor: crea la base (si hace falta y hay permisos), el usuario
     *    de la app, aplica schema.sql.
     */
    private ConexionDestino prepararDestino(Bootstrap destino, MotorBd mDe, boolean vaciar) throws SQLException {
        // --- ¿tiene datos? (para no sobrescribir sin permiso) ---
        long filasPrevias = filasActuales(destino, mDe);
        if (filasPrevias > 0 && !vaciar) {
            throw new IllegalStateException("El destino ya contiene " + filasPrevias + " fila(s). Para no "
                    + "sobrescribirlas, elige una base vacía o marca la casilla para vaciarlo.");
        }

        // --- crear la base en servidores si no existe ---
        if (mDe.esServidor() && !existeBaseEnServidor(destino)) {
            crearBaseServidor(destino);
        }

        Connection c = abrirDestino(destino, true);
        // --- aplicar el esquema canónico (todas CREATE ... IF NOT EXISTS) ---
        String nombre = mDe.esServidor() ? "schema.sql" : "schema-sqlite.sql";
        String ddl = leerEsquema(nombre);
        try (Statement st = c.createStatement()) {
            for (String s : BaseDatosAdminService.sentencias(ddl)) st.execute(s);
        }
        // --- vaciar si se pidió ---
        if (filasPrevias > 0 && vaciar) {
            fk(c, mDe, false);
            try {
                List<String> tDes = tablasPresentes(c, mDe);
                List<String> invertido = new ArrayList<>(ORDEN_TABLAS);
                java.util.Collections.reverse(invertido);
                try (Statement st = c.createStatement()) {
                    for (String t : invertido) {
                        if (tDes.contains(t)) st.execute("DELETE FROM " + q(mDe, t));
                    }
                }
            } finally {
                fk(c, mDe, true);
            }
        }
        return new ConexionDestino(c, filasPrevias == 0);
    }

    /** Cuenta las filas de las tablas de la app presentes en el destino (0 si no existe). */
    private long filasActuales(Bootstrap destino, MotorBd mDe) {
        try {
            if (mDe.esServidor() && !existeBaseEnServidor(destino)) return 0;
            try (Connection c = abrirDestino(destino, true)) {
                List<String> tDes = tablasPresentes(c, mDe);
                long total = 0;
                for (String t : ORDEN_TABLAS) if (tDes.contains(t)) total += contar(c, t);
                return total;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Copia todas las filas de una tabla y devuelve [nº filas, checksum origen]. */
    private long[] copiarTabla(Connection src, MotorBd mOt, Connection dst, MotorBd mDe, String tabla,
                               List<Columna> cols) throws SQLException {
        String lista = String.join(", ", cols.stream().map(c -> q(mDe, c.nombre())).toList());
        int n = cols.size();
        long suma = 0;
        long filas = 0;
        String select = "SELECT " + String.join(", ", cols.stream().map(c -> q(mOt, c.nombre())).toList())
                + " FROM " + q(mOt, tabla);
        String insert = "INSERT INTO " + q(mDe, tabla) + " (" + lista + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(n, "?")) + ")";
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = dst.prepareStatement(insert)) {
            boolean auto = dst.getAutoCommit();
            dst.setAutoCommit(false);
            try {
                while (rs.next()) {
                    for (int i = 0; i < n; i++) {
                        Object v = leer(rs, i + 1, cols.get(i));
                        asignar(ps, i + 1, v, mDe);
                        suma += fnv(normalizar(v));
                    }
                    ps.addBatch();
                    filas++;
                    if (filas % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
                dst.commit();
            } catch (SQLException e) {
                dst.rollback();
                throw e;
            } finally {
                dst.setAutoCommit(auto);
            }
        }
        return new long[]{filas, suma};
    }

    /** Relee una tabla del destino y devuelve [nº filas, checksum]. */
    private long[] checksumTabla(Connection c, MotorBd mDe, String tabla, List<Columna> cols) throws SQLException {
        String lista = String.join(", ", cols.stream().map(x -> q(mDe, x.nombre())).toList());
        long suma = 0;
        long filas = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + lista + " FROM " + q(mDe, tabla))) {
            while (rs.next()) {
                for (int i = 0; i < cols.size(); i++) {
                    suma += fnv(normalizar(leer(rs, i + 1, cols.get(i))));
                }
                filas++;
            }
        }
        return new long[]{filas, suma};
    }

    // ============================================================
    //  Lectura de valores y adaptación
    // ============================================================

    private Object leer(ResultSet rs, int i, Columna col) throws SQLException {
        if (col.binaria()) return rs.getBytes(i);
        if (col.fecha()) return rs.getString(i);
        Object o = rs.getObject(i);
        if (o instanceof java.sql.Timestamp t) return t.toString();
        if (o instanceof java.sql.Date d) return d.toString();
        if (o instanceof java.sql.Time t) return t.toString();
        if (o instanceof java.time.LocalDateTime d) return d.toString();
        return o;
    }

    private void asignar(PreparedStatement ps, int i, Object v, MotorBd mDe) throws SQLException {
        if (v == null) {
            ps.setObject(i, null);
        } else if (v instanceof byte[] b) {
            ps.setBytes(i, b);
        } else if (v instanceof Boolean bo) {
            ps.setInt(i, bo ? 1 : 0);
        } else if (v instanceof BigDecimal bd) {
            if (mDe == MotorBd.SQLITE) ps.setDouble(i, bd.doubleValue());
            else ps.setBigDecimal(i, bd);
        } else if (v instanceof String s) {
            ps.setString(i, s);
        } else {
            ps.setObject(i, v);
        }
    }

    /** Valor canónico para comparar entre motores (evita diferencias de formato). */
    static String normalizar(Object v) {
        if (v == null) return "\u0000";
        if (v instanceof byte[] b) return Base64.getEncoder().encodeToString(b);
        if (v instanceof Boolean bo) return bo ? "1" : "0";
        if (v instanceof BigDecimal bd) return numero(bd);
        if (v instanceof Number num) {
            try {
                return numero(new BigDecimal(num.toString()));
            } catch (NumberFormatException e) {
                return num.toString();
            }
        }
        String s = v.toString().replaceAll("\\s+", " ").trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")) {
            s = s.substring(0, 19).replace('T', ' ');
        }
        return s;
    }

    private static String numero(BigDecimal bd) {
        if (bd.signum() == 0) return "0";
        return bd.stripTrailingZeros().toPlainString();
    }

    private static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (x & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String hex(long v) {
        return String.format("%016x", v);
    }

    // ============================================================
    //  Metadatos y conexiones
    // ============================================================

    private List<String> tablasPresentes(Connection c, MotorBd m) throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = m.esServidor()
                ? "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                : "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add((rs.getString(1) + "").toLowerCase());
        }
        return out;
    }

    private List<Columna> columnasDe(Connection c, MotorBd m, String tabla) throws SQLException {
        List<Columna> out = new ArrayList<>();
        if (m.esServidor()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT column_name, data_type FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position")) {
                ps.setString(1, tabla);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Columna(rs.getString(1), tipoDesdeInfoSchema(rs.getString(2))));
                }
            }
        } else {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(" + q(MotorBd.SQLITE, tabla) + ")")) {
                while (rs.next()) out.add(new Columna(rs.getString("name"), tipoDesdeSqlite(rs.getString("type"))));
            }
        }
        return out;
    }

    private long contar(Connection c, String tabla) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabla)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void fk(Connection c, MotorBd m, boolean on) {
        try (Statement st = c.createStatement()) {
            if (m.esServidor()) st.execute("SET FOREIGN_KEY_CHECKS=" + (on ? 1 : 0));
            else st.execute("PRAGMA foreign_keys=" + (on ? "ON" : "OFF"));
        } catch (SQLException e) {
            log.warn("No se pudieron {} las claves foráneas en {}: {}", on ? "activar" : "desactivar",
                    m.clave(), e.getMessage());
        }
    }

    private boolean existeBaseEnServidor(Bootstrap b) {
        try (Connection c = abrirSinBase(b)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
                ps.setString(1, b.nombreBase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Crea la base de datos destino y (si se indicó) el usuario de la app con permisos. */
    private void crearBaseServidor(Bootstrap b) throws SQLException {
        try (Connection c = abrirSinBase(b)) {
            ejecutar(c, "CREATE DATABASE IF NOT EXISTS " + q(b.motorEnum(), b.nombreBase())
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            String uApp = credencialAppUsuario(b);
            String pApp = credencialAppPassword(b);
            if (!blanco(uApp)) {
                ejecutar(c, "CREATE USER IF NOT EXISTS '" + esc(uApp) + "'@'localhost' IDENTIFIED BY '" + esc(pApp) + "'");
                ejecutar(c, "GRANT ALL PRIVILEGES ON " + q(b.motorEnum(), b.nombreBase()) + ".* TO '"
                        + esc(uApp) + "'@'localhost'");
                ejecutar(c, "FLUSH PRIVILEGES");
            }
        }
    }

    private static String credencialAppUsuario(Bootstrap b) {
        return blanco(b.usuarioApp()) ? b.usuarioAdmin() : b.usuarioApp();
    }

    private static String credencialAppPassword(Bootstrap b) {
        return blanco(b.usuarioApp()) ? b.passwordAdmin() : b.passwordApp();
    }

    private Connection abrirSinBase(Bootstrap b) throws SQLException {
        return abrirDestino(b, false);
    }

    private Connection abrirDestino(Bootstrap b, boolean conBase) throws SQLException {
        MotorBd m = b.motorEnum();
        if (!m.esServidor()) {
            if (blanco(b.archivo())) throw new SQLException("Falta la ruta del archivo de la base de datos.");
            Path p = Path.of(b.archivo()).toAbsolutePath().normalize();
            try {
                Path dir = p.getParent();
                if (dir != null) Files.createDirectories(dir);
            } catch (IOException e) {
                throw new SQLException("No se pudo crear la carpeta del archivo: " + e.getMessage(), e);
            }
            return m.conectar(m.prefijoUrl() + p + m.parametrosUrl(), new Properties());
        }
        String base = conBase ? b.nombreBase() : "";
        String url = m.prefijoUrl() + b.host() + ":" + b.puerto() + "/" + base;
        Properties p = new Properties();
        String[] cred = credenciales(b, conBase);
        p.setProperty("user", cred[0]);
        p.setProperty("password", cred[1] == null ? "" : cred[1]);
        p.setProperty("connectTimeout", "6000");
        p.setProperty("loginTimeout", "6");
        return m.conectar(url, p);
    }

    /** Para crear la base usa el usuario admin; para el resto, el de la app si se indicó. */
    private String[] credenciales(Bootstrap b, boolean conBase) {
        if (!blanco(b.usuarioAdmin())) return new String[]{b.usuarioAdmin(), b.passwordAdmin()};
        return new String[]{b.usuarioApp(), b.passwordApp()};
    }

    private boolean tieneTablas(Path archivo, MotorBd m) {
        try (Connection c = MotorBd.SQLITE.conectar(
                MotorBd.SQLITE.prefijoUrl() + archivo.toAbsolutePath() + MotorBd.SQLITE.parametrosUrl(),
                new Properties())) {
            return !tablasPresentes(c, m).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    //  Utilidades
    // ============================================================

    private boolean mismoOrigen(MotorBd mOt, Bootstrap destino) {
        MotorBd mDe = destino.motorEnum();
        if (mOt != mDe) return false;
        if (!mOt.esServidor()) {
            String a = Path.of(db.archivoSqlite()).toAbsolutePath().normalize().toString();
            String b = destino.archivo() == null ? "" : Path.of(destino.archivo()).toAbsolutePath().normalize().toString();
            return a.equalsIgnoreCase(b);
        }
        return eq(db.conexion().host(), destino.host())
                && eq(db.conexion().puerto(), destino.puerto())
                && eq(db.conexion().base(), destino.nombreBase());
    }

    private Map<String, Object> resumenMotor(MotorBd m, String base, String archivo) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("motor", m.clave());
        o.put("motor_etiqueta", m.etiqueta());
        o.put("es_servidor", m.esServidor());
        o.put("base", base == null ? "" : base);
        o.put("archivo", m.esServidor() ? null : archivo);
        return o;
    }

    private String leerEsquema(String nombre) {
        Path sql = cfgRespaldos.raizApp().resolve("database").resolve(nombre);
        if (!Files.isRegularFile(sql)) {
            throw new IllegalStateException("No se encontró el esquema del destino: " + sql);
        }
        try {
            return Files.readString(sql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + sql + ": " + e.getMessage());
        }
    }

    private void ejecutar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void cerrar(Connection c) {
        if (c == null) return;
        try {
            if (!c.getAutoCommit()) c.rollback();
        } catch (SQLException ignored) {
            // sin transacción que deshacer
        }
        try {
            c.close();
        } catch (SQLException ignored) {
            // ya cerrada
        }
    }

    private static String q(MotorBd m, String ident) {
        return m == MotorBd.SQLITE ? "\"" + ident + "\"" : "`" + ident + "`";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equalsIgnoreCase(b == null ? "" : b);
    }

    private static boolean blanco(String s) {
        return s == null || s.isBlank();
    }

    private static String mensaje(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int tipoDesdeInfoSchema(String tipo) {
        if (tipo == null) return Types.OTHER;
        String t = tipo.toLowerCase();
        if (t.contains("blob") || t.contains("binary")) return Types.VARBINARY;
        if (t.contains("datetime") || t.contains("timestamp")) return Types.TIMESTAMP;
        if (t.equals("date")) return Types.DATE;
        if (t.equals("time")) return Types.TIME;
        if (t.contains("decimal") || t.contains("numeric")) return Types.DECIMAL;
        if (t.contains("int")) return Types.BIGINT;
        if (t.contains("double") || t.contains("float") || t.contains("real")) return Types.DOUBLE;
        if (t.contains("bit") || t.contains("bool")) return Types.BIT;
        return Types.VARCHAR;
    }

    private static int tipoDesdeSqlite(String tipo) {
        if (tipo == null) return Types.VARCHAR;
        String t = tipo.toUpperCase();
        if (t.contains("BLOB") || t.contains("BINARY")) return Types.VARBINARY;
        if (t.contains("INT")) return Types.BIGINT;
        if (t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") || t.contains("NUM") || t.contains("DEC"))
            return Types.DOUBLE;
        return Types.VARCHAR;
    }
}
