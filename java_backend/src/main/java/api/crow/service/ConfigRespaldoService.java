package api.crow.service;

import api.crow.domain.ConfigRespaldo;
import api.crow.repository.ConfigRespaldoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuración de respaldos (frecuencia, carpetas y binarios de MariaDB).
 * Mismo patrón que {@link ConfigService}: un único registro cifrado, con
 * valores por defecto sensatos y semilla al arrancar.
 *
 * Por defecto: respaldo automático ACTIVADO cada 24 h, con los automáticos en
 * {@code <raíz de la app>/backups/auto} y los manuales (separados) en
 * {@code <raíz de la app>/backups/manual}.
 */
@Slf4j
@Service
@Order(2)
@RequiredArgsConstructor
public class ConfigRespaldoService implements ApplicationRunner {

    public static final int INTERVALO_HORAS_MIN = 1;
    public static final int INTERVALO_HORAS_MAX = 720;   // 30 días
    public static final int RETENCION_MIN = 0;           // 0 = conservar todas
    public static final int RETENCION_MAX = 1000;
    public static final int RETENCION_DEFECTO = 10;
    public static final int INTERVALO_HORAS_DEFECTO = 24;
    public static final String HORA_DIARIA_DEFECTO = "03:00";

    private static final String[] BIN_DUMP = {"mariadb-dump", "mysqldump"};
    private static final String[] BIN_CLIENTE = {"mariadb", "mysql"};

    private final ConfigRespaldoRepository repo;
    private final ConfigCifradoService cifrado;
    private final BaseDatosService db;

    /** true cuando algún campo de config_respaldo no se pudo descifrar (clave cambiada). */
    private volatile boolean claveInvalida = false;

    public record Config(boolean autoActivo, String modo, int intervaloHoras, String horaDiaria,
                         String dirAuto, String dirManual, String binDir, int retencion) {}

    // ---- lectura ----

    public Config cargar() {
        try {
            return repo.findById(ConfigRespaldo.ID).map(this::descifrar).orElseGet(this::defecto);
        } catch (Exception e) {
            // config_respaldo todavía no existe (base sin crear): usar defaults.
            return defecto();
        }
    }

    public boolean claveInvalida() {
        return claveInvalida;
    }

    /** Carpeta de los respaldos automáticos (creada si no existe). */
    public Path dirAuto() {
        return prepararDir(cargar().dirAuto());
    }

    /** Carpeta de los respaldos manuales (creada si no existe). Separada a propósito. */
    public Path dirManual() {
        return prepararDir(cargar().dirManual());
    }

    /** Ruta completa a mariadb-dump(.exe) o mysqldump(.exe), o null si no se encuentra. */
    public String binDump() {
        if (db.esArchivo()) return null;   // SQLite no usa herramientas externas
        return resolverBin(cargar().binDir(), BIN_DUMP);
    }

    /** Ruta completa al cliente mariadb(.exe)/mysql(.exe) para restaurar, o null. */
    public String binCliente() {
        if (db.esArchivo()) return null;
        return resolverBin(cargar().binDir(), BIN_CLIENTE);
    }

    /** Igual que {@link #binDump()} pero resolviendo en una carpeta dada (sin tocar la BD). */
    public String binDumpEn(String binDir) {
        return resolverBin(binDir, BIN_DUMP);
    }

    /** Igual que {@link #binCliente()} pero resolviendo en una carpeta dada (sin tocar la BD). */
    public String binClienteEn(String binDir) {
        return resolverBin(binDir, BIN_CLIENTE);
    }

    /** Raíz de la app (donde vive java_backend/ y angular_frontend/), robusta ante el cwd. */
    public Path raizApp() {
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path porCwd = buscarRaiz(cwd);
        if (porCwd != null) return porCwd;
        Path base = codigoBase();
        if (base != null) {
            Path porCodigo = buscarRaiz(Files.isDirectory(base) ? base : base.getParent());
            if (porCodigo != null) return porCodigo;
        }
        return cwd;
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

    /** Valores por defecto (sin tocar la BD): usados por la semilla y en modo degradado. */
    public Config defecto() {
        Path raiz = raizApp();
        String bin = dirBinDetectado();
        return new Config(true, ConfigRespaldo.MODO_INTERVALO, INTERVALO_HORAS_DEFECTO,
                HORA_DIARIA_DEFECTO,
                raiz.resolve("backups").resolve("auto").toString(),
                raiz.resolve("backups").resolve("manual").toString(),
                bin == null ? "" : bin,
                RETENCION_DEFECTO);
    }

    // ---- escritura ----

    public Config guardar(Config c) {
        if (c == null) throw new IllegalArgumentException("Datos de configuración vacíos");

        String modo = c.modo() == null ? ConfigRespaldo.MODO_INTERVALO : c.modo().trim().toLowerCase();
        if (!ConfigRespaldo.MODO_INTERVALO.equals(modo) && !ConfigRespaldo.MODO_DIARIO.equals(modo)) {
            throw new IllegalArgumentException("La frecuencia debe ser 'intervalo' (cada N horas) o 'diario' (a una hora)");
        }
        if (c.intervaloHoras() < INTERVALO_HORAS_MIN || c.intervaloHoras() > INTERVALO_HORAS_MAX) {
            throw new IllegalArgumentException("El intervalo debe estar entre " + INTERVALO_HORAS_MIN
                    + " y " + INTERVALO_HORAS_MAX + " horas");
        }
        String hora = c.horaDiaria() == null ? "" : c.horaDiaria().trim();
        if (ConfigRespaldo.MODO_DIARIO.equals(modo) && !hora.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new IllegalArgumentException("La hora diaria debe tener el formato HH:mm (ej. 03:00)");
        }
        if (c.retencion() < RETENCION_MIN || c.retencion() > RETENCION_MAX) {
            throw new IllegalArgumentException("La retención debe estar entre " + RETENCION_MIN
                    + " y " + RETENCION_MAX + " (0 = conservar todos)");
        }
        String dirAuto = validarDir(c.dirAuto(), "La carpeta de respaldos automáticos");
        String dirManual = validarDir(c.dirManual(), "La carpeta de respaldos manuales");
        if (dirAuto.equalsIgnoreCase(dirManual)) {
            throw new IllegalArgumentException("Las carpetas de automáticos y manuales deben ser distintas "
                    + "(así no se mezclan en el historial)");
        }
        String binDir = c.binDir() == null ? "" : c.binDir().trim();
        // SQLite no usa herramientas externas: la carpeta bin no aplica.
        if (!db.esArchivo()) {
            if (!binDir.isEmpty()) {
                if (!Files.isDirectory(Path.of(binDir))) {
                    throw new IllegalArgumentException("La carpeta de programas de la base de datos no existe: " + binDir);
                }
                if (resolverBin(binDir, BIN_DUMP) == null) {
                    throw new IllegalArgumentException("No se encontró la herramienta de volcado "
                            + "(mysqldump o mariadb-dump) en " + binDir);
                }
            } else if (dirBinDetectado() == null) {
                throw new IllegalArgumentException("No se encontró la herramienta de volcado (mysqldump o mariadb-dump) "
                        + "en el sistema. Indica la carpeta bin de tu instalación (MariaDB o MySQL).");
            }
        }

        ConfigRespaldo e = repo.findById(ConfigRespaldo.ID).orElseGet(ConfigRespaldo::new);
        e.setId(ConfigRespaldo.ID);
        e.setAutoActivoCifrado(cifrado.cifrar(String.valueOf(c.autoActivo())));
        e.setModoCifrado(cifrado.cifrar(modo));
        e.setIntervaloHorasCifrado(cifrado.cifrar(String.valueOf(c.intervaloHoras())));
        e.setHoraDiariaCifrado(cifrado.cifrar(hora.isEmpty() ? HORA_DIARIA_DEFECTO : hora));
        e.setDirAutoCifrado(cifrado.cifrar(dirAuto));
        e.setDirManualCifrado(cifrado.cifrar(dirManual));
        e.setBinDirCifrado(cifrado.cifrar(binDir.isEmpty() ? null : binDir));
        e.setRetencionCifrado(cifrado.cifrar(String.valueOf(c.retencion())));
        e.setActualizadoEn(LocalDateTime.now());
        repo.save(e);
        log.info("Configuración de respaldos actualizada: auto={} modo={} intervalo={}h hora={} retención={}",
                c.autoActivo(), modo, c.intervaloHoras(), hora, c.retencion());
        return cargar();
    }

    // ---- arranque ----

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (repo.findById(ConfigRespaldo.ID).isPresent()) return;
            guardar(defecto());
            log.info("Respaldos: se creó config_respaldo con valores por defecto (auto cada {}h, "
                    + "carpetas en {})", INTERVALO_HORAS_DEFECTO, raizApp().resolve("backups"));
        } catch (Exception e) {
            log.warn("Respaldos: no se pudo sembrar config_respaldo todavía "
                    + "(¿base de datos sin crear? Se puede crear desde Configuración → Datos). Detalle: {}",
                    e.getMessage());
        }
    }

    // ---- internos ----

    private Config descifrar(ConfigRespaldo e) {
        boolean[] fallo = {false};
        Config def = defecto();
        String modo = texto(e.getModoCifrado(), fallo);
        if (modo == null || (!ConfigRespaldo.MODO_INTERVALO.equals(modo) && !ConfigRespaldo.MODO_DIARIO.equals(modo))) {
            modo = def.modo();
        }
        String hora = texto(e.getHoraDiariaCifrado(), fallo);
        if (hora == null || !hora.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) hora = def.horaDiaria();
        String binDir = texto(e.getBinDirCifrado(), fallo);
        if (binDir == null) binDir = def.binDir(); else binDir = binDir.trim();

        Config c = new Config(
                booleano(texto(e.getAutoActivoCifrado(), fallo), def.autoActivo()),
                modo,
                (int) entero(texto(e.getIntervaloHorasCifrado(), fallo), def.intervaloHoras(),
                        INTERVALO_HORAS_MIN, INTERVALO_HORAS_MAX, "intervalo"),
                hora,
                noVacio(texto(e.getDirAutoCifrado(), fallo), def.dirAuto()),
                noVacio(texto(e.getDirManualCifrado(), fallo), def.dirManual()),
                binDir,
                (int) entero(texto(e.getRetencionCifrado(), fallo), def.retencion(),
                        RETENCION_MIN, RETENCION_MAX, "retención"));
        if (fallo[0] != claveInvalida) {
            if (fallo[0]) {
                log.warn("Respaldos: no se pudo descifrar config_respaldo con la clave maestra actual; "
                        + "se usan valores por defecto. GUARDAR desde la UI re-cifra con la clave vigente.");
            }
        }
        claveInvalida = fallo[0];
        return c;
    }

    private String texto(byte[] dato, boolean[] fallo) {
        if (dato == null || dato.length == 0) return null;
        try {
            return cifrado.descifrar(dato);
        } catch (IllegalStateException ex) {
            fallo[0] = true;
            return null;
        }
    }

    private static boolean booleano(String texto, boolean defecto) {
        if (texto == null || texto.isBlank()) return defecto;
        return Boolean.parseBoolean(texto.trim());
    }

    private static String noVacio(String texto, String defecto) {
        return (texto == null || texto.isBlank()) ? defecto : texto.trim();
    }

    private long entero(String texto, long defecto, long min, long max, String nombre) {
        if (texto == null || texto.isBlank()) return defecto;
        try {
            long v = Long.parseLong(texto.trim());
            if (v < min || v > max) return defecto;
            return v;
        } catch (NumberFormatException e) {
            log.warn("Respaldos: {} no es numérico ({}); se usa {}", nombre, texto, defecto);
            return defecto;
        }
    }

    /** Valida/crea la carpeta y confirma que se puede escribir. Devuelve la ruta absoluta. */
    private String validarDir(String ruta, String nombre) {
        if (ruta == null || ruta.isBlank()) throw new IllegalArgumentException(nombre + " es obligatoria");
        Path p = Path.of(ruta.trim()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (IOException ex) {
            throw new IllegalArgumentException(nombre + " no se pudo crear: " + p + " (" + ex.getMessage() + ")");
        }
        if (!Files.isDirectory(p)) throw new IllegalArgumentException(nombre + " no es una carpeta: " + p);
        if (!Files.isWritable(p)) throw new IllegalArgumentException(nombre + " no tiene permiso de escritura: " + p);
        return p.toString();
    }

    /** Igual que validarDir pero sin lanzar: para uso interno (no romper una operación en curso). */
    private Path prepararDir(String ruta) {
        Path p = Path.of(ruta).toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (IOException ex) {
            log.warn("Respaldos: no se pudo crear la carpeta {}: {}", p, ex.getMessage());
        }
        return p;
    }

    /** Carpeta bin de MariaDB detectada (la del primer binario encontrado), o null. */
    private String dirBinDetectado() {
        String dump = resolverBin(null, BIN_DUMP);
        return dump == null ? null : Path.of(dump).getParent().toString();
    }

    /**
     * Busca un ejecutable por nombre en: carpeta configurada → MARIADB_BIN →
     * F:\MariaDB10.11\bin → PATH → instalaciones típicas en Archivos de programa.
     */
    private String resolverBin(String binDirCfg, String... nombres) {
        Set<Path> dirs = new LinkedHashSet<>();
        if (binDirCfg != null && !binDirCfg.isBlank()) dirs.add(Path.of(binDirCfg.trim()));
        String env = System.getenv("MARIADB_BIN");
        if (env != null && !env.isBlank()) dirs.add(Path.of(env.trim()));
        dirs.add(Path.of("F:\\MariaDB10.11\\bin"));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String p : path.split(File.pathSeparator)) {
                if (!p.isBlank()) dirs.add(Path.of(p.trim()));
            }
        }
        dirs.addAll(glob("C:\\Program Files", "MariaDB*", "bin"));
        dirs.addAll(glob("C:\\Program Files (x86)", "MariaDB*", "bin"));
        // MySQL 8 (instalador típico en Archivos de programa).
        dirs.addAll(glob("C:\\Program Files\\MySQL", "MySQL Server*", "bin"));
        dirs.addAll(glob("C:\\Program Files (x86)\\MySQL", "MySQL Server*", "bin"));
        String mysqlHome = System.getenv("MYSQL_HOME");
        if (mysqlHome != null && !mysqlHome.isBlank()) {
            dirs.add(Path.of(mysqlHome.trim()).resolve("bin"));
        }

        for (Path d : dirs) {
            for (String n : nombres) {
                for (String ext : new String[]{"", ".exe"}) {
                    Path exe = d.resolve(n + ext);
                    if (Files.isRegularFile(exe)) return exe.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    /** Carpetas que casan con base/glob/bin (ej. C:\Program Files, MariaDB*, bin). */
    private static List<Path> glob(String base, String patron, String hijo) {
        List<Path> out = new ArrayList<>();
        Path b = Path.of(base);
        if (!Files.isDirectory(b)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(b, patron)) {
            for (Path p : ds) {
                Path bin = p.resolve(hijo);
                if (Files.isDirectory(bin)) out.add(bin);
            }
        } catch (IOException ignored) {
            // sin permisos o sin coincidencias: se omite
        }
        return out;
    }

    private static Path codigoBase() {
        try {
            java.net.URI uri = ConfigRespaldoService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return Path.of(uri);
        } catch (Exception e) {
            return null;
        }
    }
}
