package api.crow.repository;

import api.crow.domain.Publicacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublicacionRepository extends JpaRepository<Publicacion, Long> {

    List<Publicacion> findByTituloIdOrderByCreadoEnDesc(Long tituloId);
}