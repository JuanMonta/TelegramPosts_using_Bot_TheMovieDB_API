package api.crow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "titulos")
@Getter
@Setter
public class Titulo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tmdb_id", nullable = false, unique = true)
    private Integer tmdbId;

    @Column(name = "tipo", nullable = false, length = 10)
    private String tipo;

    @Column(name = "titulo_original", nullable = false)
    private String tituloOriginal;

    @Column(name = "titulo_latino")
    private String tituloLatino;

    @Column(name = "sinopsis", columnDefinition = "TEXT")
    private String sinopsis;

    @Column(name = "anio")
    private Integer anio;

    @Column(name = "generos")
    private String generos;

    @Column(name = "rating", precision = 3, scale = 1)
    private BigDecimal rating;

    @Column(name = "vote_count")
    private Integer voteCount;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "backdrop_path")
    private String backdropPath;

    @Column(name = "poster_local_path")
    private String posterLocalPath;

    @Column(name = "ultima_actualizacion")
    private LocalDateTime ultimaActualizacion;

    @Column(name = "datos_json", columnDefinition = "json")
    private String datosJson;

    @Column(name = "creado_en", updatable = false)
    private LocalDateTime creadoEn;

    @PrePersist
    void prePersist() {
        if (creadoEn == null) creadoEn = LocalDateTime.now();
        if (ultimaActualizacion == null) ultimaActualizacion = LocalDateTime.now();
        if (tipo == null) tipo = "movie";
    }
}