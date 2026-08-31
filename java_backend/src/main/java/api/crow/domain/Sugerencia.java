package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "sugerencias")
@Getter
@Setter
public class Sugerencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_id", nullable = false)
    private Integer tmdbId;

    @Column(name = "media_type", nullable = false, length = 10)
    private String mediaType;

    @Column(name = "titulo", nullable = false)
    private String titulo;

    @Column(name = "titulo_original")
    private String tituloOriginal;

    @Column(name = "anio", length = 4)
    private String anio;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "origen", nullable = false, length = 10)
    private String origen;

    @Column(name = "cargado_en")
    private LocalDateTime cargadoEn;

    @PrePersist
    void prePersist() {
        if (origen == null) origen = "guardado";
        if (cargadoEn == null) cargadoEn = LocalDateTime.now();
    }
}