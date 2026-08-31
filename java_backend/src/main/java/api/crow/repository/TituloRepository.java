package api.crow.repository;

import api.crow.domain.Titulo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TituloRepository extends JpaRepository<Titulo, Long> {

    Optional<Titulo> findByTmdbId(Integer tmdbId);

    boolean existsByTmdbId(Integer tmdbId);
}