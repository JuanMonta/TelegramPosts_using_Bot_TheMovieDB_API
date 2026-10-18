package api.crow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una ejecución de respaldo (automática o manual) o de una operación destructiva
 * (restauración / importación). Nace en {@link #ESTADO_EN_PROGRESO} con un
 * {@link #tokenEjecucion} único; si la app muere a mitad (apagón) la fila queda
 * 'en_progreso' y al siguiente arranque se marca {@link #ESTADO_INTERRUMPIDO}
 * (control de integridad). El archivo se escribe primero como *.part y solo al
 * terminar (tamaño + checksum) se renombra al nombre final: nunca queda un
 * respaldo a medias con nombre definitivo.
 */
@Entity
@Table(name = "respaldos")
@Getter
@Setter
public class Respaldo {

    public static final String TIPO_AUTOMATICO = "automatico";
    public static final String TIPO_MANUAL = "manual";

    public static final String ESTADO_EN_PROGRESO = "en_progreso";
    public static final String ESTADO_COMPLETADO = "completado";
    public static final String ESTADO_FALLO = "fallo";
    public static final String ESTADO_INTERRUMPIDO = "interrumpido";

    public static final String MOTIVO_MANUAL = "manual";
    public static final String MOTIVO_PROGRAMADO = "programado";
    public static final String MOTIVO_PRE_RESTAURACION = "pre_restauracion";
    public static final String MOTIVO_PRE_IMPORTACION = "pre_importacion";

    /** ¿El estado es terminal (ya no va a cambiar solo)? */
    public static boolean esTerminal(String estado) {
        return ESTADO_COMPLETADO.equals(estado)
                || ESTADO_FALLO.equals(estado)
                || ESTADO_INTERRUMPIDO.equals(estado);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tipo", nullable = false, length = 20)
    private String tipo;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado;

    @Column(name = "motivo", nullable = false, length = 40)
    private String motivo;

    /** Nombre del archivo, sin carpeta (ej. respaldo_auto_2026-10-18_03-00-00.sql.gz). */
    @Column(name = "archivo", nullable = false, length = 255)
    private String archivo;

    /** Ruta completa del .sql.gz final. */
    @Column(name = "ruta", nullable = false, length = 1000)
    private String ruta;

    @Column(name = "tamano_bytes")
    private Long tamanoBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "motor", nullable = false, length = 40)
    private String motor = "mariadb";

    /** Versión del backend que lo generó (para saber con qué reimportarlo). */
    @Column(name = "version", length = 40)
    private String version;

    @Column(name = "mensaje", columnDefinition = "TEXT")
    private String mensaje;

    @Column(name = "iniciado_en", nullable = false)
    private LocalDateTime iniciadoEn;

    @Column(name = "terminado_en")
    private LocalDateTime terminadoEn;

    @Column(name = "duracion_ms")
    private Long duracionMs;

    @Column(name = "verificado_en")
    private LocalDateTime verificadoEn;

    @Column(name = "token_ejecucion", nullable = false, length = 36)
    private String tokenEjecucion;
}
