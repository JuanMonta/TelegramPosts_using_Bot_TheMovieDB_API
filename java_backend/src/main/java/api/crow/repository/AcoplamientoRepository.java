package api.crow.repository;

import api.crow.domain.Acoplamiento;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AcoplamientoRepository extends JpaRepository<Acoplamiento, Long> {
    List<Acoplamiento> findByActivoTrueOrderByNombreAsc();

    @EntityGraph(attributePaths = "canales")
    Optional<Acoplamiento> findConCanalesById(Long id);

    @EntityGraph(attributePaths = "canales")
    List<Acoplamiento> findAllConCanalesByActivoTrueOrderByNombreAsc();
}
