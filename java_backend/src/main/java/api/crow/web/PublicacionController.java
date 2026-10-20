package api.crow.web;

import api.crow.domain.Canal;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.domain.PublicacionMedia;
import api.crow.repository.AcoplamientoRepository;
import api.crow.repository.CanalRepository;
import api.crow.repository.ColaPublicacionRepository;
import api.crow.repository.LinkVideoRepository;
import api.crow.repository.PublicacionMediaRepository;
import api.crow.repository.PublicacionRepository;
import api.crow.repository.TituloRepository;
import api.crow.service.ColaPublicacionService;
import api.crow.service.MediaService;
import api.crow.service.TelegramModoService;
import api.crow.service.TelegramPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/publicaciones")
@RequiredArgsConstructor
public class PublicacionController {

    public static final String TIPO_PERSONALIZADO = PublicacionViewAssembler.TIPO_PERSONALIZADO;

    private final PublicacionRepository publicacionRepository;
    private final TituloRepository tituloRepository;
    private final LinkVideoRepository linkVideoRepository;
    private final PublicacionMediaRepository mediaRepository;
    private final ColaPublicacionRepository colaRepository;
    private final CanalRepository canalRepository;
    private final AcoplamientoRepository acoplamientoRepository;
    private final TelegramPublisher telegram;
    private final TelegramModoService telegramModo;
    private final MediaService media;
    private final ColaPublicacionService colaService;
    private final PublicacionViewAssembler vistas;

    /** tituloId null = post personalizado (sin TMDB). */
    record PublicacionRequest(Long tituloId, String tipo, String caption) {}

    @GetMapping
    public List<Map<String, Object>> listar() {
        return vistas.listar();
    }

    /**
     * Catálogo completo para el gestor: títulos + estado de publicación + posts
     * personalizados, con una proyección ligera y conteos por estado. El filtrado
     * por texto/estado y el orden se hacen en el frontend.
     */
    @GetMapping("/catalogo")
    public ResponseEntity<Map<String, Object>> catalogo() {
        return ResponseEntity.ok(vistas.catalogo());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detalle(@PathVariable Long id) {
        return publicacionRepository.findById(id)
                .map(p -> ResponseEntity.ok(vistas.toMap(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody PublicacionRequest req) {
        boolean personalizado = TIPO_PERSONALIZADO.equalsIgnoreCase(req.tipo()) || req.tituloId() == null;
        if (personalizado) {
            Publicacion p = new Publicacion();
            p.setTipo(TIPO_PERSONALIZADO);
            p.setCaption(req.caption());
            p.setEstado("borrador");
            publicacionRepository.save(p);
            return ResponseEntity.ok(vistas.toMap(p));
        }
        return tituloRepository.findById(req.tituloId())
                .map(t -> {
                    Publicacion p = new Publicacion();
                    p.setTipo(PublicacionViewAssembler.TIPO_TITULO);
                    p.setTitulo(t);
                    p.setCaption(req.caption());
                    p.setEstado("borrador");
                    publicacionRepository.save(p);
                    return ResponseEntity.ok(vistas.toMap(p));
                })
                .orElseGet(() -> ResponseEntity.badRequest().body(Map.of("error", "El título no existe")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> editar(@PathVariable Long id, @RequestBody PublicacionRequest req) {
        return publicacionRepository.findById(id)
                .map(p -> {
                    if (req.caption() != null) p.setCaption(req.caption());
                    publicacionRepository.save(p);
                    return ResponseEntity.ok(vistas.toMap(p));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        if (p.getTitulo() == null) {
            // El post personalizado se borra por completo: su mensaje también sale del canal.
            if ("publicado".equals(p.getEstado())) {
                try {
                    telegram.borrarPost(p);
                } catch (Exception ignore) {
                    // el mensaje ya no existe (borrado manual): el post se elimina igual
                }
            }
            for (PublicacionMedia m : mediaRepository.findByPublicacionId(id)) {
                media.borrar(m.getArchivo());
            }
            linkVideoRepository.deleteAll(linkVideoRepository.findByPublicacionId(id));
        }
        if (p.getTitulo() != null) {
            tituloRepository.deleteById(p.getTitulo().getId());
            return ResponseEntity.noContent().build();
        }
        publicacionRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Envía el borrador al canal de Telegram y lo marca como publicado. */
    @PostMapping("/{id}/publicar")
    public ResponseEntity<?> publicar(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long acoplamientoId = body != null && body.get("acoplamientoId") != null
                ? Long.valueOf(body.get("acoplamientoId").toString()) : null;
        Publicacion p = publicacionRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (colaRepository.tieneActivo(id)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "El post está programado en la cola. Cancelá la programación para publicarlo manualmente."));
        }
        if ("publicado".equals(p.getEstado())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "El post ya está publicado. Recargá el estado para ver las acciones del post publicado."));
        }
        List<PublicacionMedia> med = vistas.mediaDe(p);
        long limite = colaService.limiteDe(p);
        long longitud = p.getCaption() == null ? 0 : p.getCaption().codePointCount(0, p.getCaption().length());
        if (longitud > limite) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    ColaPublicacionService.errorCaptionLargo(longitud, limite).replace("Acórtalo en el editor y vuelve a intentar.", "Acórtalo en el editor antes de publicar.")));
        }
        List<LinkVideo> links = vistas.linksDe(p);
        String modo = telegramModo.modoActual();
        List<String> canales;
        if (acoplamientoId != null) {
            canales = canalRepository.findAllById(acoplamientoRepository.findConCanalesById(acoplamientoId)
                            .map(a -> a.getCanales().stream().map(c -> c.getId()).toList())
                            .orElse(List.of()))
                    .stream()
                    .filter(c -> Boolean.TRUE.equals(c.getActivo()) && modo.equals(c.getTipo()))
                    .map(Canal::getChatId).toList();
            if (canales.isEmpty()) {
                String etiqueta = TelegramModoService.MODO_TEST.equals(modo) ? "PRUEBA" : "REAL";
                return ResponseEntity.badRequest().body(Map.of("error",
                        "El conjunto no tiene destinos de modo " + etiqueta + ". Agregalos en Configuración."));
            }
        } else {
            canales = List.of(telegramModo.canalActivo());
        }
        if (canales.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No hay canales de destino configurados."));
        }
        List<Resource> fotos = vistas.recursosDe(med);
        Map<String, Object> resp = null;
        for (String canal : canales) {
            resp = telegram.enviarPost(p, links, fotos, canal);
        }
        p.setEstado("publicado");
        p.setModo(telegramModo.modoActual());
        p.setAcoplamientoId(acoplamientoId);
        p.setTelegramMessageId(((Number) resp.get("message_id")).longValue());
        p.setTelegramChatId(resp.get("chat_id").toString());
        p.setFechaPublicacion(LocalDateTime.now());
        publicacionRepository.save(p);
        return ResponseEntity.ok(vistas.toMap(p));
    }

    /** Actualiza el post original en el canal con el caption/botones actuales (sin repostear). */
    @PostMapping("/{id}/editar")
    public ResponseEntity<?> editarEnCanal(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"publicado".equals(p.getEstado()) || p.getTelegramMessageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El post no está publicado en el canal todavía."));
        }
        List<LinkVideo> links = vistas.linksDe(p);
        telegram.editarPost(p, links, p.getTitulo() != null || !vistas.mediaDe(p).isEmpty());
        p.setFechaEditado(LocalDateTime.now());
        publicacionRepository.save(p);
        return ResponseEntity.ok(vistas.toMap(p));
    }

    /**
     * Elimina el mensaje del canal y devuelve el registro a borrador
     * (deshacer la publicación sin perder el caption).
     */
    @PostMapping("/{id}/eliminar-del-canal")
    public ResponseEntity<?> eliminarDelCanal(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"publicado".equals(p.getEstado()) || p.getTelegramMessageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El post no está publicado en el canal todavía."));
        }
        telegram.borrarPost(p);
        p.setEstado("borrador");
        p.setTelegramMessageId(null);
        p.setTelegramChatId(null);
        p.setFechaPublicacion(null);
        p.setFechaEditado(null);
        publicacionRepository.save(p);
        return ResponseEntity.ok(vistas.toMap(p));
    }

    /** Elimina el post original del canal y publica uno nuevo actualizado. */
    @PostMapping("/{id}/republicar")
    public ResponseEntity<?> republicar(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"publicado".equals(p.getEstado())) {
            return ResponseEntity.badRequest().body(Map.of("error", "El post no está publicado en el canal todavía."));
        }
        List<LinkVideo> links = vistas.linksDe(p);
        List<Resource> fotos = vistas.recursosDe(vistas.mediaDe(p));
        Map<String, Object> resp = telegram.republicar(p, links, fotos);
        p.setEstado("publicado");
        p.setModo(telegramModo.modoActual());
        p.setTelegramMessageId(((Number) resp.get("message_id")).longValue());
        p.setTelegramChatId(resp.get("chat_id").toString());
        p.setFechaPublicacion(LocalDateTime.now());
        p.setFechaRepublicado(LocalDateTime.now());
        publicacionRepository.save(p);
        return ResponseEntity.ok(vistas.toMap(p));
    }

    // ---- Links / botones de un post personalizado ----

    record LinkRequest(String texto, String url, @com.fasterxml.jackson.annotation.JsonProperty("terabox_filename") String teraboxFilename, Integer orden) {}

    @GetMapping("/{id}/links")
    public ResponseEntity<?> links(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        List<Map<String, Object>> out = new ArrayList<>();
        for (LinkVideo l : vistas.linksDe(p)) out.add(vistas.linkToMap(l));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/links")
    public ResponseEntity<?> agregarLink(@PathVariable Long id, @RequestBody LinkRequest req) {
        if (req.texto() == null || req.texto().isBlank() || req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "texto y url son obligatorios"));
        }
        return publicacionRepository.findById(id)
                .map(p -> {
                    LinkVideo link = new LinkVideo();
                    if (p.getTitulo() != null) {
                        link.setTitulo(p.getTitulo());
                    } else {
                        link.setPublicacionId(p.getId());
                    }
                    link.setTextoBoton(req.texto().trim());
                    link.setUrl(req.url().trim());
                    if (req.teraboxFilename() != null && !req.teraboxFilename().isBlank()) {
                        link.setTeraboxFilename(req.teraboxFilename().trim());
                    }
                    link.setOrden(req.orden() == null ? vistas.linksDe(p).size() : req.orden());
                    linkVideoRepository.save(link);
                    return ResponseEntity.ok(vistas.linkToMap(link));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/links/{linkId}")
    public ResponseEntity<?> editarLink(@PathVariable Long id, @PathVariable Long linkId, @RequestBody LinkRequest req) {
        return linkVideoRepository.findById(linkId)
                .filter(l -> vistas.perteneceA(l, id))
                .map(l -> {
                    if (req.texto() != null && !req.texto().isBlank()) l.setTextoBoton(req.texto().trim());
                    if (req.url() != null && !req.url().isBlank()) l.setUrl(req.url().trim());
                    if (req.teraboxFilename() != null) l.setTeraboxFilename(req.teraboxFilename().isBlank() ? null : req.teraboxFilename().trim());
                    if (req.orden() != null) l.setOrden(req.orden());
                    linkVideoRepository.save(l);
                    return ResponseEntity.ok(vistas.linkToMap(l));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/links/{linkId}")
    public ResponseEntity<?> eliminarLink(@PathVariable Long id, @PathVariable Long linkId) {
        return linkVideoRepository.findById(linkId)
                .filter(l -> vistas.perteneceA(l, id))
                .map(l -> {
                    linkVideoRepository.delete(l);
                    return ResponseEntity.noContent().<Map<String, Object>>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
