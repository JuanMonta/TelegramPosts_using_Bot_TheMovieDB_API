package api.crow.web;

import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.repository.LinkVideoRepository;
import api.crow.repository.PublicacionRepository;
import api.crow.repository.TituloRepository;
import api.crow.config.TelegramProperties;
import api.crow.service.TelegramPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/publicaciones")
@RequiredArgsConstructor
public class PublicacionController {

    private final PublicacionRepository publicacionRepository;
    private final TituloRepository tituloRepository;
    private final LinkVideoRepository linkVideoRepository;
    private final TelegramPublisher telegram;
    private final TelegramProperties telegramProps;

    record PublicacionRequest(Long tituloId, String caption) {}

    @GetMapping
    public List<Map<String, Object>> listar() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Publicacion p : publicacionRepository.findAll()) {
            out.add(toMap(p));
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detalle(@PathVariable Long id) {
        return publicacionRepository.findById(id)
                .map(p -> ResponseEntity.ok(toMap(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody PublicacionRequest req) {
        if (req.tituloId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tituloId es obligatorio"));
        }
        return tituloRepository.findById(req.tituloId())
                .map(t -> {
                    Publicacion p = new Publicacion();
                    p.setTitulo(t);
                    p.setCaption(req.caption());
                    p.setEstado("borrador");
                    publicacionRepository.save(p);
                    return ResponseEntity.ok(toMap(p));
                })
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of("error", "El título no existe")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> editar(@PathVariable Long id, @RequestBody PublicacionRequest req) {
        return publicacionRepository.findById(id)
                .map(p -> {
                    if (req.caption() != null) p.setCaption(req.caption());
                    publicacionRepository.save(p);
                    return ResponseEntity.ok(toMap(p));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        if (!publicacionRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        publicacionRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Envía el borrador al canal de Telegram y lo marca como publicado. */
    @PostMapping("/{id}/publicar")
    public ResponseEntity<?> publicar(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id)
                .orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            List<LinkVideo> links = linkVideoRepository.findByTituloIdOrderByOrdenAsc(p.getTitulo().getId());
            Map<String, Object> resp = telegram.sendPhoto(p, links);
            p.setEstado("publicado");
            p.setTelegramMessageId(((Number) resp.get("message_id")).longValue());
            p.setTelegramChatId(resp.get("chat_id").toString());
            p.setFechaPublicacion(LocalDateTime.now());
            publicacionRepository.save(p);
            return ResponseEntity.ok(toMap(p));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Actualiza el post original en el canal con el caption/botones actuales (sin repostear). */
    @PostMapping("/{id}/editar")
    public ResponseEntity<?> editarEnCanal(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id)
                .orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"publicado".equals(p.getEstado()) || p.getTelegramMessageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El post no está publicado en el canal todavía."));
        }
        try {
            List<LinkVideo> links = linkVideoRepository.findByTituloIdOrderByOrdenAsc(p.getTitulo().getId());
            telegram.editarPost(p, links);
            p.setFechaEditado(LocalDateTime.now());
            publicacionRepository.save(p);
            return ResponseEntity.ok(toMap(p));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Elimina el post original del canal y publica uno nuevo actualizado. */
    @PostMapping("/{id}/republicar")
    public ResponseEntity<?> republicar(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id)
                .orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"publicado".equals(p.getEstado())) {
            return ResponseEntity.badRequest().body(Map.of("error", "El post no está publicado en el canal todavía."));
        }
        try {
            List<LinkVideo> links = linkVideoRepository.findByTituloIdOrderByOrdenAsc(p.getTitulo().getId());
            Map<String, Object> resp = telegram.republicar(p, links);
            p.setEstado("publicado");
            p.setTelegramMessageId(((Number) resp.get("message_id")).longValue());
            p.setTelegramChatId(resp.get("chat_id").toString());
            p.setFechaPublicacion(LocalDateTime.now());
            p.setFechaRepublicado(LocalDateTime.now());
            publicacionRepository.save(p);
            return ResponseEntity.ok(toMap(p));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> toMap(Publicacion p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("titulo_id", p.getTitulo().getId());
        m.put("titulo", p.getTitulo().getTituloLatino() != null
                ? p.getTitulo().getTituloLatino()
                : p.getTitulo().getTituloOriginal());
        m.put("caption", p.getCaption());
        m.put("estado", p.getEstado());
        m.put("telegram_message_id", p.getTelegramMessageId());
        m.put("telegram_chat_id", p.getTelegramChatId());
        m.put("chat_dedicado", Boolean.TRUE);
        m.put("telegram_grupo_chat_id", telegramProps.discussionGroupId());
        m.put("fecha_publicacion", p.getFechaPublicacion() == null ? null : p.getFechaPublicacion().toString());
        m.put("fecha_editado", p.getFechaEditado() == null ? null : p.getFechaEditado().toString());
        m.put("fecha_republicado", p.getFechaRepublicado() == null ? null : p.getFechaRepublicado().toString());
        return m;
    }
}