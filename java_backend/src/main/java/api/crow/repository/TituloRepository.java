package api.crow.repository;

import api.crow.domain.Titulo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TituloRepository extends JpaRepository<Titulo, Long> {

    Optional<Titulo> findByTmdbId(Integer tmdbId);

    boolean existsByTmdbId(Integer tmdbId);

    /** Proyección ligera para el catálogo: no arrastra datos_json/sinopsis (pesados). */
    interface CatalogoFila {
        Long getId();
        String getTipo();
        String getTituloOriginal();
        String getTituloLatino();
        Integer getAnio();
        String getGeneros();
        BigDecimal getRating();
        String getPosterPath();
        LocalDateTime getCreadoEn();
    }

    @Query("SELECT t.id AS id, t.tipo AS tipo, t.tituloOriginal AS tituloOriginal, " +
            "t.tituloLatino AS tituloLatino, t.anio AS anio, t.generos AS generos, " +
            "t.rating AS rating, t.posterPath AS posterPath, t.creadoEn AS creadoEn " +
            "FROM Titulo t")
    List<CatalogoFila> catalogo();
}