package api.crow.repository;

import api.crow.domain.Comentario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ComentarioRepository extends JpaRepository<Comentario, Long> {

    List<Comentario> findByPublicacionIdOrderByFechaAsc(Long publicacionId);

    long countByPublicacionId(Long publicacionId);

    Optional<Comentario> findByTelegramMessageId(Long telegramMessageId);

    boolean existsByTelegramMessageId(Long telegramMessageId);

    /** Devuelve mapa publicacion_id -> conteo de comentarios para múltiples publicaciones. */
    @Query("SELECT c.publicacionId as pubId, COUNT(c) as cnt FROM Comentario c WHERE c.publicacionId IN :ids GROUP BY c.publicacionId")
    Map<Long, Long> conteoPorPublicaciones(@Param("ids") List<Long> ids);
}
