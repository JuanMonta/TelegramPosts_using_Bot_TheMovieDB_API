package api.crow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Único registro (id = 1) con la configuración de la aplicación. TODOS los
 * valores se guardan cifrados con AES-256-GCM (ver ConfigCifradoService): en la
 * tabla no hay texto plano — ni el token, ni los canales, ni los números.
 * Los valores se descifran recién al leerlos desde ConfigService.
 */
@Entity
@Table(name = "config_app")
@Getter
@Setter
public class ConfigApp {

    /** Siempre existe un solo registro: id = 1. */
    public static final long ID = 1L;

    @Id
    @Column(name = "id")
    private Long id = ID;

    @Column(name = "bot_token_cifrado", columnDefinition = "VARBINARY(1024)")
    private byte[] botTokenCifrado;

    @Column(name = "canal_principal_cifrado", columnDefinition = "VARBINARY(512)")
    private byte[] canalPrincipalCifrado;

    @Column(name = "canal_prueba_cifrado", columnDefinition = "VARBINARY(512)")
    private byte[] canalPruebaCifrado;

    @Column(name = "tmdb_token_cifrado", columnDefinition = "VARBINARY(512)")
    private byte[] tmdbTokenCifrado;

    @Column(name = "limite_caption_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] limiteCaptionCifrado;

    @Column(name = "intervalo_cola_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] intervaloColaCifrado;

    @Column(name = "colgada_min_cifrado", columnDefinition = "VARBINARY(128)")
    private byte[] colgadaMinCifrado;

    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;
}
