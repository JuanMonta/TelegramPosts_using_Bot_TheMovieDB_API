package api.crow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Único registro (id = 1) con la configuración de respaldos. Igual que
 * {@link ConfigApp}, TODOS los valores se guardan cifrados con AES-256-GCM
 * (ver ConfigCifradoService) y se descifran recién en memoria. Las rutas y la
 * programación no son secretas, pero se cifran por consistencia con el resto.
 */
@Entity
@Table(name = "config_respaldo")
@Getter
@Setter
public class ConfigRespaldo {

    /** Siempre existe un solo registro: id = 1. */
    public static final long ID = 1L;

    public static final String MODO_INTERVALO = "intervalo";
    public static final String MODO_DIARIO = "diario";

    @Id
    @Column(name = "id")
    private Long id = ID;

    @Column(name = "auto_activo_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] autoActivoCifrado;

    @Column(name = "modo_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] modoCifrado;

    @Column(name = "intervalo_horas_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] intervaloHorasCifrado;

    @Column(name = "hora_diaria_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] horaDiariaCifrado;

    @Column(name = "dir_auto_cifrado", columnDefinition = "VARBINARY(4096)")
    private byte[] dirAutoCifrado;

    @Column(name = "dir_manual_cifrado", columnDefinition = "VARBINARY(4096)")
    private byte[] dirManualCifrado;

    @Column(name = "bin_dir_cifrado", columnDefinition = "VARBINARY(4096)")
    private byte[] binDirCifrado;

    @Column(name = "retencion_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] retencionCifrado;

    // 'creado_en' NO se mapea: lo llena la BD con su DEFAULT CURRENT_TIMESTAMP
    // (si se mapeara, Hibernate enviaría NULL y violaría el NOT NULL).
    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;
}
