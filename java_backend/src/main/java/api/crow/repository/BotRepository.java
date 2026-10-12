package api.crow.repository;

import api.crow.domain.Bot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotRepository extends JpaRepository<Bot, Long> {
    List<Bot> findByActivoTrueOrderByNombreAsc();
}
