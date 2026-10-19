package api.crow.config;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;

/**
 * Motores de base de datos que la app entiende. Cada uno reúne lo que cambia
 * entre ellos: prefijo de la URL JDBC, driver, dialecto de Hibernate, puerto por
 * defecto y los nombres de las herramientas de volcado/cliente usadas por los
 * respaldos.
 *
 * El valor se guarda en {@code config-db.yaml} (clave {@code app.bootstrap-bd.motor})
 * y en {@code spring.datasource.*}. Si falta, se asume {@link #MARIADB} para no
 * romper instalaciones anteriores.
 *
 * Nota: SQLite (motor de archivo, sin servidor) se añadirá en una fase posterior;
 * esta enumeración ya lo deja representado con {@link #esServidor()} = false.
 */
public enum MotorBd {

    MARIADB("mariadb", "jdbc:mariadb://", "org.mariadb.jdbc.Driver",
            "org.hibernate.dialect.MariaDBDialect", 3306, "MariaDB", true,
            new String[]{"mariadb-dump", "mysqldump"},
            new String[]{"mariadb", "mysql"}),

    MYSQL("mysql", "jdbc:mysql://", "com.mysql.cj.jdbc.Driver",
            "org.hibernate.dialect.MySQLDialect", 3306, "MySQL", true,
            new String[]{"mysqldump", "mariadb-dump"},
            new String[]{"mysql", "mariadb"}),

    /**
     * SQLite: motor de ARCHIVO, sin servidor. La "base de datos" es un único
     * archivo .db; no hay host, puerto, usuario ni GRANT. El prefijo de la URL
     * incluye directamente la ruta del archivo (no usa "//").
     */
    SQLITE("sqlite", "jdbc:sqlite:", "org.sqlite.JDBC",
            "org.hibernate.community.dialect.SQLiteDialect", 0, "SQLite", false,
            new String[]{}, new String[]{});

    private final String clave;
    private final String prefijoUrl;
    private final String driver;
    private final String dialecto;
    private final int puertoDefecto;
    private final String etiqueta;
    private final boolean esServidor;
    private final String[] nombresDump;
    private final String[] nombresCliente;

    MotorBd(String clave, String prefijoUrl, String driver, String dialecto, int puertoDefecto,
            String etiqueta, boolean esServidor, String[] nombresDump, String[] nombresCliente) {
        this.clave = clave;
        this.prefijoUrl = prefijoUrl;
        this.driver = driver;
        this.dialecto = dialecto;
        this.puertoDefecto = puertoDefecto;
        this.etiqueta = etiqueta;
        this.esServidor = esServidor;
        this.nombresDump = nombresDump;
        this.nombresCliente = nombresCliente;
    }

    /** Clave en minúsculas (ej. "mysql"), tal como vive en el yaml. */
    public String clave() {
        return clave;
    }

    /** Prefijo de la URL JDBC (ej. "jdbc:mysql://"). */
    public String prefijoUrl() {
        return prefijoUrl;
    }

    /** Nombre de clase del driver JDBC. */
    public String driver() {
        return driver;
    }

    /** Dialecto de Hibernate para este motor. */
    public String dialecto() {
        return dialecto;
    }

    public int puertoDefecto() {
        return puertoDefecto;
    }

    /** Nombre legible para la UI (ej. "MariaDB"). */
    public String etiqueta() {
        return etiqueta;
    }

    /** false para motores de archivo (SQLite): sin host, puerto, usuario ni GRANT. */
    public boolean esServidor() {
        return esServidor;
    }

    public String[] nombresDump() {
        return nombresDump.clone();
    }

    public String[] nombresCliente() {
        return nombresCliente.clone();
    }

    /** Puerto por defecto formateado (o "" para motores sin puerto). */
    public String puertoDefectoTexto() {
        return esServidor ? String.valueOf(puertoDefecto) : "";
    }

    /**
     * Parámetros extra para la URL de la app (vacío si no hacen falta). En MySQL 8
     * el usuario puede quedar con autenticación {@code caching_sha2_password}; sin
     * SSL eso exige permitir la descarga de la clave pública del servidor.
     */
    public String parametrosUrl() {
        if (this == MYSQL) {
            return "?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8";
        }
        if (this == SQLITE) {
            // Journal en WAL (mejor concurrencia), claves foráneas activas, espera
            // corta si el archivo está bloqueado y fechas como TEXTO (yyyy-MM-dd
            // HH:mm:ss.SSS): el modo "integer" del driver choca con columnas TEXT.
            return "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000&date_class=text";
        }
        return "";
    }

    /** Interpreta el valor guardado; tolera mayúsculas y espacios; por defecto MariaDB. */
    public static MotorBd desde(String valor) {
        if (valor != null) {
            String v = valor.trim().toLowerCase();
            for (MotorBd m : values()) {
                if (m.clave.equals(v)) return m;
            }
        }
        return MARIADB;
    }

    /** Detecta el motor a partir de una URL JDBC (p. ej. "jdbc:mysql://..."). */
    public static MotorBd desdeUrl(String url) {
        if (url != null) {
            String u = url.toLowerCase();
            for (MotorBd m : values()) {
                if (u.startsWith(m.prefijoUrl.toLowerCase())) return m;
            }
            // El driver de MariaDB también puede abrir una URL jdbc:mysql://;
            // si aparece 'mysql' sin ser 'mariadb', se asume MySQL.
            if (u.startsWith("jdbc:mysql:")) return MYSQL;
        }
        return MARIADB;
    }

    /** Todas las claves válidas (para validaciones y mensajes). */
    public static String claves() {
        return String.join(", ", Arrays.stream(values()).map(MotorBd::clave).toList());
    }

    /**
     * Abre una conexión usando EXACTAMENTE el driver de este motor (no vía
     * {@link java.sql.DriverManager}, que elige el primer driver registrado que
     * acepte la URL). El driver de MariaDB también acepta {@code jdbc:mysql://},
     * así que hay que instanciar el driver correcto por su clase.
     */
    public Connection conectar(String url, Properties props) throws SQLException {
        try {
            Driver d = (Driver) Class.forName(driver).getDeclaredConstructor().newInstance();
            return d.connect(url, props);
        } catch (SQLException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("No se pudo cargar el controlador de " + etiqueta + " (" + driver + ")", e);
        }
    }
}
