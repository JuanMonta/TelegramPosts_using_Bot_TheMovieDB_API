package api.crow.repository;

import api.crow.domain.Publicacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PublicacionRepository extends JpaRepository<Publicacion, Long> {

    List<Publicacion> findByTituloIdOrderByCreadoEnDesc(Long tituloId);

    /** Busca una publicación por el message_id de Telegram del canal. */
    Optional<Publicacion> findByTelegramMessageId(Long telegramMessageId);

    /** Busca por canal + message_id: desambigua mensajes entre el canal real y el de pruebas. */
    Optional<Publicacion> findByTelegramChatIdAndTelegramMessageId(String telegramChatId, Long telegramMessageId);

    /** Trae todas las publicaciones con su título (evita N+1 al montar el catálogo). */
    @Query("SELECT p FROM Publicacion p JOIN FETCH p.titulo ORDER BY p.creadoEn DESC")
    List<Publicacion> findAllConTitulo();
}