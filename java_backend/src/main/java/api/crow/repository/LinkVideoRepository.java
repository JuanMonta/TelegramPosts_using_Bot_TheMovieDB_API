package api.crow.repository;

import api.crow.domain.LinkVideo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LinkVideoRepository extends JpaRepository<LinkVideo, Long> {

    List<LinkVideo> findByTituloIdOrderByOrdenAsc(Long tituloId);

    /** Enlaces propios de un post personalizado. */
    List<LinkVideo> findByPublicacionIdOrderByOrdenAsc(Long publicacionId);

    List<LinkVideo> findByPublicacionId(Long publicacionId);
}