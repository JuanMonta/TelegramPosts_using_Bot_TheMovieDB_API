package api.crow.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Imagen adjunta a un post. La app guarda una COPIA PROCESADA (comprimida y
 * redimensionada) en storage/media y aquí solo se referencia por nombre de
 * archivo. Así el original puede borrarse del equipo del usuario sin afectar
 * al post.
 */
@Entity
@Table(name = "publicacion_media")
@Getter
@Setter
public class PublicacionMedia {

    public static final String ORIGEN_SUBIDA = "subida";
    public static final String ORIGEN_TMDB = "tmdb";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "publicacion_id", nullable = false)
    @NotNull(message = "La imagen debe pertenecer a una publicación.")
    private Long publicacionId;

    @Column(name = "orden")
    @PositiveOrZero(message = "El orden no puede ser negativo.")
    private Integer orden = 0;

    /** Ruta relativa dentro de storage/media (solo el nombre de archivo). */
    @Column(name = "archivo", length = 500)
    @Size(max = 500, message = "La ruta del archivo no puede superar los 500 caracteres.")
    private String archivo;

    /** 'subida' (equipo del usuario) | 'tmdb'. */
    @Column(name = "origen", length = 12)
    @Size(max = 12, message = "El origen no puede superar los 12 caracteres.")
    private String origen = ORIGEN_SUBIDA;

    @Column(name = "tmdb_tipo", length = 8)
    @Size(max = 8, message = "El tipo de TMDB no puede superar los 8 caracteres.")
    private String tmdbTipo;

    @Column(name = "tmdb_path", length = 255)
    @Size(max = 255, message = "La ruta de TMDB no puede superar los 255 caracteres.")
    private String tmdbPath;

    @Column(name = "ancho")
    private Integer ancho;

    @Column(name = "alto")
    private Integer alto;

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (orden == null) orden = 0;
        if (origen == null || origen.isBlank()) origen = ORIGEN_SUBIDA;
    }
}
