package api.crow.repository;

import api.crow.domain.ColaPublicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ColaPublicacionRepository extends JpaRepository<ColaPublicacion, Long> {

    /** Pendientes por publicar, los más próximos primero. */
    List<ColaPublicacion> findByEstadoOrderByProgramadoParaAsc(String estado);

    /** Debidas (programado_para <= ahora) con su publicación y título ya precargados. */
    @Query("SELECT c FROM ColaPublicacion c JOIN FETCH c.publicacion p JOIN FETCH p.titulo "
            + "WHERE c.estado = :estado AND c.programadoPara IS NOT NULL AND c.programadoPara <= :ahora "
            + "ORDER BY c.programadoPara ASC")
    List<ColaPublicacion> findDebidas(@Param("estado") String estado, @Param("ahora") LocalDateTime ahora);

    /** Estado crítico (pendiente/publicando) o terminado, para listados. */
    List<ColaPublicacion> findByEstadoInOrderByProgramadoParaAsc(List<String> estados);

    /** Items activos (sin cancelar ni pasados fallidos) con su publicación y título. */
    @Query("SELECT c FROM ColaPublicacion c JOIN FETCH c.publicacion p JOIN FETCH p.titulo ORDER BY c.programadoPara ASC")
    List<ColaPublicacion> findAllConPublicacion();

    /** True si la publicación ya tiene un item activo (pendiente o en curso). */
    @Query("SELECT COUNT(c) > 0 FROM ColaPublicacion c WHERE c.publicacion.id = :publicacionId AND c.estado IN ('pendiente', 'publicando')")
    boolean tieneActivo(@Param("publicacionId") Long publicacionId);

    /** Item activo de una publicación concreta (para el estado del editor). */
    @Query("SELECT c FROM ColaPublicacion c JOIN FETCH c.publicacion p JOIN FETCH p.titulo WHERE c.publicacion.id = :publicacionId AND c.estado IN ('pendiente', 'publicando')")
    Optional<ColaPublicacion> findActivoPorPublicacion(@Param("publicacionId") Long publicacionId);

    /** Items activos con su publicación (solo para mapear el catálogo sin N+1). */
    @Query("SELECT c FROM ColaPublicacion c JOIN FETCH c.publicacion WHERE c.estado IN ('pendiente', 'publicando')")
    List<ColaPublicacion> findAtivosConPublicacion();

    /**
     * Reclama el item de forma atómica: solo el hilo que logra pasar de
     * 'pendiente' a 'publicando' (1 fila afectada) procede a enviar. Así dos
     * instancias del backend nunca publican dos veces el mismo post.
     */
    @Modifying
    @Query("UPDATE ColaPublicacion c SET c.estado = 'publicando', c.ultimoIntento = :ahora WHERE c.id = :id AND c.estado = 'pendiente'")
    int reclamar(@Param("id") Long id, @Param("ahora") LocalDateTime ahora);

    /**
     * Libera items que quedaron 'publicando' por un reinicio/crash a mitad del
     * envío: vuelven a 'pendiente' si su último intento es antiguo.
     */
    @Modifying
    @Query("UPDATE ColaPublicacion c SET c.estado = 'pendiente' WHERE c.estado = 'publicando' AND c.ultimoIntento IS NOT NULL AND c.ultimoIntento < :limite")
    int liberarColgadas(@Param("limite") LocalDateTime limite);
}