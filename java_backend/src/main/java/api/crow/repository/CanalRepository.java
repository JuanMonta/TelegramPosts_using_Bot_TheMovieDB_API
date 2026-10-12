package api.crow.repository;

import api.crow.domain.Canal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CanalRepository extends JpaRepository<Canal, Long> {
    List<Canal> findByActivoTrueOrderByNombreAsc();
    List<Canal> findByTipoAndActivoTrueOrderByNombreAsc(String tipo);
    boolean existsByChatIdAndTipo(String chatId, String tipo);
}
