package api.crow.repository;

import api.crow.domain.Sugerencia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SugerenciaRepository extends JpaRepository<Sugerencia, Long> {

    Optional<Sugerencia> findByTmdbIdAndMediaType(Integer tmdbId, String mediaType);

    long countByOrigen(String origen);
}