package api.crow.repository;

import api.crow.domain.PublicacionMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;

public interface PublicacionMediaRepository extends JpaRepository<PublicacionMedia, Long> {

    List<PublicacionMedia> findByPublicacionIdOrderByOrdenAscIdAsc(Long publicacionId);

    long countByPublicacionId(Long publicacionId);

    List<PublicacionMedia> findByPublicacionId(Long publicacionId);

    /** Devuelve mapa publicacion_id -> conteo de imágenes para múltiples publicaciones. */
    @Query("SELECT m.publicacionId as pubId, COUNT(m) as cnt FROM PublicacionMedia m WHERE m.publicacionId IN :ids GROUP BY m.publicacionId")
    Map<Long, Long> conteoPorPublicaciones(@Param("ids") List<Long> ids);
}
