package api.crow.service;

import api.crow.config.TelegramProperties;
import api.crow.config.TmdbProperties;
import api.crow.domain.ConfigApp;
import api.crow.repository.ConfigAppRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Configuración editable de la aplicación (antes vivía en application.yaml).
 *
 * - Al arrancar, si la tabla config_app está vacía se crea con los valores del
 *   yaml (los canales y el token que hoy ya tenés en application-local.yaml), de
 *   modo que el comportamiento no cambia en absoluto.
 * - Después de ese seed, la tabla manda: editar el yaml ya no afecta (salvo
 *   fallback si un valor queda vacío).
 * - Se lea como se lea, NADA se expone en texto plano en la BD: cada campo se
 *   escribe cifrado (AES-256-GCM) y se descifra recién en memoria.
 * - El token NUNCA sale por la API: GET devuelve solo una máscara (últimos 4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService implements ApplicationRunner {

    public static final int LIMITE_CAPTION_DEFECTO = 1024;
    public static final int LIMITE_CAPTION_MAX = 4096;
    public static final long INTERVALO_COLA_DEFECTO_MS = 30_000;
    public static final long INTERVALO_COLA_MIN_MS = 1_000;
    public static final long INTERVALO_COLA_MAX_MS = 3_600_000;
    public static final int COLGADA_DEFECTO_MIN = 5;
    public static final int COLGADA_MAX_MIN = 1440;

    private final ConfigAppRepository repo;
    private final ConfigCifradoService cifrado;
    private final TelegramProperties props;
    private final TmdbProperties tmdbProps;

    /** true cuando algún campo de config_app no se pudo descifrar con la clave actual. */
    private volatile boolean claveInvalida = false;

    /** Valores descifrados de la configuración. */
    public record Configuracion(String botToken, String canalPrincipal, String canalPrueba, String tmdbToken,
                                int limiteCaption, long intervaloColaMs, int colgadaMinutos) {}

    /** Payload de guardado: null = conservar el valor actual. */
    public record Guardar(String botToken, String canalPrincipal, String canalPrueba, String tmdbToken,
                          Integer limiteCaption, Long intervaloColaMs, Integer colgadaMinutos) {}

    // ---- lectura ----

    /** Lee y descifra la configuración vigente (siempre fresca desde la BD). */
    public Configuracion cargar() {
        try {
            return repo.findById(ConfigApp.ID)
                    .map(this::descifrar)
                    .orElseGet(this::defectoConYaml);
        } catch (Exception e) {
            // La base de datos todavía no existe (primer arranque): no es un error
            // fatal; se usan los valores del yaml hasta que se cree la base.
            return defectoConYaml();
        }
    }

    /** Token efectivo: el de la BD si está cargado; si no, el del yaml/env. */
    public String tokenBot() {
        String t = cargar().botToken();
        return (t != null && !t.isBlank()) ? t : props.botToken();
    }

    /** Canal principal efectivo (config → fallback yaml). */
    public String canalPrincipal() {
        String c = cargar().canalPrincipal();
        return (c != null && !c.isBlank()) ? c : props.channelId();
    }

    /** Canal de prueba efectivo (config → fallback yaml). Vacío si no hay ninguno. */
    public String canalPrueba() {
        String c = cargar().canalPrueba();
        if (c != null && !c.isBlank()) return c;
        return props.testChannelId();
    }

    /** Token de TMDB efectivo (config → fallback yaml/env). */
    public String tmdbToken() {
        String t = cargar().tmdbToken();
        return (t != null && !t.isBlank()) ? t : tmdbProps.apiKey();
    }

    public int limiteCaption() {
        return cargar().limiteCaption();
    }

    public long intervaloColaMs() {
        return cargar().intervaloColaMs();
    }

    public int colgadaMinutos() {
        return cargar().colgadaMinutos();
    }

    /** Máscara para exponer el token por la API: nunca el valor real. */
    public static String enmascarar(String secreto) {
        if (secreto == null || secreto.isBlank()) return null;
        String s = secreto.trim();
        return "••••••••" + (s.length() <= 4 ? s.length() + " caracteres" : s.substring(s.length() - 4));
    }

    /** ¿Los valores guardados no se pueden descifrar con la clave maestra actual? */
    public boolean claveInvalida() {
        return claveInvalida;
    }

    // ---- escritura ----

    /** Guarda los cambios cifrados. Lanza IllegalArgumentException con mensaje en español si algo no va. */
    public Configuracion guardar(Guardar g) {
        ConfigApp c = repo.findById(ConfigApp.ID).orElseGet(this::nueva);
        if (g == null) throw new IllegalArgumentException("Datos de configuración vacíos");

        if (g.botToken() != null) {
            String token = g.botToken().trim();
            if (token.length() > 512) throw new IllegalArgumentException("El token es demasiado largo (máx. 512)");
            c.setBotTokenCifrado(cifrado.cifrar(token.isEmpty() ? null : token));
        }
        if (g.canalPrincipal() != null) {
            String canal = g.canalPrincipal().trim();
            validarIdChat(canal, "El canal principal");
            c.setCanalPrincipalCifrado(cifrado.cifrar(canal.isEmpty() ? null : canal));
        }
        if (g.canalPrueba() != null) {
            String canal = g.canalPrueba().trim();
            validarIdChat(canal, "El canal de prueba");
            c.setCanalPruebaCifrado(cifrado.cifrar(canal.isEmpty() ? null : canal));
        }
        if (g.tmdbToken() != null) {
            String token = g.tmdbToken().trim();
            if (token.length() > 512) throw new IllegalArgumentException("El token de TMDB es demasiado largo (máx. 512)");
            c.setTmdbTokenCifrado(cifrado.cifrar(token.isEmpty() ? null : token));
        }
        if (g.limiteCaption() != null) {
            int v = g.limiteCaption();
            if (v < 1 || v > LIMITE_CAPTION_MAX) {
                throw new IllegalArgumentException("El límite del caption debe estar entre 1 y " + LIMITE_CAPTION_MAX);
            }
            c.setLimiteCaptionCifrado(cifrado.cifrar(String.valueOf(v)));
        }
        if (g.intervaloColaMs() != null) {
            long v = g.intervaloColaMs();
            if (v < INTERVALO_COLA_MIN_MS || v > INTERVALO_COLA_MAX_MS) {
                throw new IllegalArgumentException("El intervalo de la cola debe estar entre "
                        + (INTERVALO_COLA_MIN_MS / 1000) + "s y " + (INTERVALO_COLA_MAX_MS / 60000) + "min");
            }
            c.setIntervaloColaCifrado(cifrado.cifrar(String.valueOf(v)));
        }
        if (g.colgadaMinutos() != null) {
            int v = g.colgadaMinutos();
            if (v < 1 || v > COLGADA_MAX_MIN) {
                throw new IllegalArgumentException("Los minutos para liberar items colgados deben estar entre 1 y " + COLGADA_MAX_MIN);
            }
            c.setColgadaMinCifrado(cifrado.cifrar(String.valueOf(v)));
        }
        c.setActualizadoEn(LocalDateTime.now());
        repo.save(c);
        log.info("Configuración actualizada ({}); los valores quedan cifrados en config_app", resumen(g));
        return cargar();
    }

    // ---- arranque ----

    /** Seed: la primera vez crea el registro con los valores del yaml. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            if (repo.findById(ConfigApp.ID).isPresent()) return;
            ConfigApp c = nueva();
            if (props.botToken() != null && !props.botToken().isBlank()) {
                c.setBotTokenCifrado(cifrado.cifrar(props.botToken().trim()));
            }
            if (props.channelId() != null && !props.channelId().isBlank()) {
                c.setCanalPrincipalCifrado(cifrado.cifrar(props.channelId().trim()));
            }
            if (props.testChannelId() != null && !props.testChannelId().isBlank()) {
                c.setCanalPruebaCifrado(cifrado.cifrar(props.testChannelId().trim()));
            }
            if (tmdbProps.apiKey() != null && !tmdbProps.apiKey().isBlank()) {
                c.setTmdbTokenCifrado(cifrado.cifrar(tmdbProps.apiKey().trim()));
            }
            c.setLimiteCaptionCifrado(cifrado.cifrar(String.valueOf(LIMITE_CAPTION_DEFECTO)));
            c.setIntervaloColaCifrado(cifrado.cifrar(String.valueOf(INTERVALO_COLA_DEFECTO_MS)));
            c.setColgadaMinCifrado(cifrado.cifrar(String.valueOf(COLGADA_DEFECTO_MIN)));
            c.setActualizadoEn(LocalDateTime.now());
            repo.save(c);
            log.info("Configuración: se creó config_app con los valores de application.yaml (cifrados)");
        } catch (Exception e) {
            log.warn("Configuración: no se pudo sembrar config_app todavía "
                    + "(¿base de datos sin crear? Se puede crear desde Configuración → Datos). Detalle: {}",
                    e.getMessage());
        }
    }

    // ---- internos ----

    private ConfigApp nueva() {
        ConfigApp c = new ConfigApp();
        c.setId(ConfigApp.ID);
        return c;
    }

    /** Descifra la fila. Si la clave no coincide, cada campo cae a su valor por
     *  defecto (yaml) en vez de reventar el arranque: el flag clave_invalida()
     *  avisa por la API para que la UI pida re-ingresar y re-cifrar al guardar. */
    private Configuracion descifrar(ConfigApp c) {
        boolean[] fallo = { false };
        Configuracion cfg = new Configuracion(
                descifrarSeguro(c.getBotTokenCifrado(), fallo),
                descifrarSeguro(c.getCanalPrincipalCifrado(), fallo),
                descifrarSeguro(c.getCanalPruebaCifrado(), fallo),
                descifrarSeguro(c.getTmdbTokenCifrado(), fallo),
                (int) entero(descifrarSeguro(c.getLimiteCaptionCifrado(), fallo), LIMITE_CAPTION_DEFECTO, 1, LIMITE_CAPTION_MAX, "límite del caption"),
                entero(descifrarSeguro(c.getIntervaloColaCifrado(), fallo), INTERVALO_COLA_DEFECTO_MS, INTERVALO_COLA_MIN_MS, INTERVALO_COLA_MAX_MS, "intervalo de la cola"),
                (int) entero(descifrarSeguro(c.getColgadaMinCifrado(), fallo), COLGADA_DEFECTO_MIN, 1, COLGADA_MAX_MIN, "minutos para liberar items colgados"));
        if (fallo[0] != claveInvalida) {
            if (fallo[0]) {
                log.warn("Configuración: no se pudieron descifrar los valores de config_app con la clave "
                        + "maestra actual (¿se cambió CROW_CLAVE_MAESTRA, se borró clave_maestra.bin o se "
                        + "arrancó desde otro directorio?). Se usan los valores de application.yaml; "
                        + "GUARDAR desde la UI re-cifra con la clave vigente.");
            } else {
                log.info("Configuración: los valores de config_app vuelven a descifrarse correctamente.");
            }
        }
        claveInvalida = fallo[0];
        return cfg;
    }

    private String descifrarSeguro(byte[] dato, boolean[] fallo) {
        try {
            return cifrado.descifrar(dato);
        } catch (IllegalStateException e) {
            fallo[0] = true;
            return null;
        }
    }

    /** Configuración por defecto cuando la tabla todavía no existe (pre-seed). */
    private Configuracion defectoConYaml() {
        return new Configuracion(
                props.botToken(),
                props.channelId(),
                props.testChannelId(),
                tmdbProps.apiKey(),
                LIMITE_CAPTION_DEFECTO, INTERVALO_COLA_DEFECTO_MS, COLGADA_DEFECTO_MIN);
    }

    private long entero(String texto, long defecto, long min, long max, String nombre) {
        if (texto == null || texto.isBlank()) return defecto;
        try {
            long v = Long.parseLong(texto.trim());
            if (v < min || v > max) {
                log.warn("Configuración: {} = {} fuera de rango [{}..{}]; se usa {}", nombre, v, min, max, defecto);
                return defecto;
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("Configuración: {} no es numérico ({}); se usa {}", nombre, texto, defecto);
            return defecto;
        }
    }

    private void validarIdChat(String valor, String nombre) {
        if (valor.isEmpty()) return; // vacío = limpiar
        boolean ok = valor.startsWith("@")
                || valor.matches("-?\\d{5,25}");
        if (!ok) {
            throw new IllegalArgumentException(nombre
                    + " debe ser @nombredecanal o un id numérico (ej. -1001234567890)");
        }
    }

    private String resumen(Guardar g) {
        StringBuilder sb = new StringBuilder();
        if (g.botToken() != null) sb.append("token, ");
        if (g.canalPrincipal() != null) sb.append("canal principal, ");
        if (g.canalPrueba() != null) sb.append("canal de prueba, ");
        if (g.tmdbToken() != null) sb.append("token TMDB, ");
        if (g.limiteCaption() != null) sb.append("límite caption, ");
        if (g.intervaloColaMs() != null) sb.append("intervalo cola, ");
        if (g.colgadaMinutos() != null) sb.append("min colgada, ");
        if (sb.length() == 0) return "sin cambios";
        sb.setLength(sb.length() - 2);
        return sb.toString();
    }
}
