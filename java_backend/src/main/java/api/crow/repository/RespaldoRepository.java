package api.crow.repository;

import api.crow.domain.Respaldo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RespaldoRepository extends JpaRepository<Respaldo, Long> {

    /** Historial completo, lo más reciente primero. */
    List<Respaldo> findAllByOrderByIniciadoEnDesc();

    /** Filas que quedaron 'en_progreso' (candidatas a interrupción por apagón). */
    List<Respaldo> findByEstado(String estado);

    /** Ejecuciones con id mayor a uno dado (para avisar solo lo nuevo). */
    List<Respaldo> findByIdGreaterThanOrderByIniciadoEnAsc(Long id);

    /** Último respaldo de un tipo (para calcular cuándo toca el próximo). */
    Optional<Respaldo> findFirstByTipoOrderByIniciadoEnDesc(String tipo);

    /** Automáticos ya completados, del más nuevo al más viejo (para retención). */
    @Query("SELECT r FROM Respaldo r WHERE r.tipo = :tipo AND r.estado = :estado ORDER BY r.iniciadoEn DESC")
    List<Respaldo> findTerminadosPorTipo(@Param("tipo") String tipo, @Param("estado") String estado);
}
