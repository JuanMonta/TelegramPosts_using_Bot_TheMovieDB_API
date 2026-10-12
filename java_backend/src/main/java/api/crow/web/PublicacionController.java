package api.crow.web;

import api.crow.domain.ColaPublicacion;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.domain.Canal;
import api.crow.repository.ColaPublicacionRepository;
import api.crow.repository.ComentarioRepository;
import api.crow.repository.LinkVideoRepository;
import api.crow.repository.PublicacionRepository;
import api.crow.repository.TituloRepository;
import api.crow.repository.AcoplamientoRepository;
import api.crow.repository.CanalRepository;
import api.crow.config.TelegramProperties;
import api.crow.service.ColaPublicacionService;
import api.crow.service.ConfigService;
import api.crow.service.TelegramModoService;
import api.crow.service.TelegramPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final ComentarioRepository comentarioRepository;
    private final ColaPublicacionRepository colaRepository;
    private final TelegramPublisher telegram;
    private final TelegramProperties telegramProps;
    private final TelegramModoService telegramModo;
    private final ConfigService config;
    private final AcoplamientoRepository acoplamientoRepository;
    private final CanalRepository canalRepository;

    record PublicacionRequest(Long tituloId, String caption) {}

    @GetMapping
    public List<Map<String, Object>> listar() {
        String modoActivo = telegramModo.modoActual();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Publicacion p : publicacionRepository.findAll()) {
            if (!visibleEnModo(p, modoActivo)) continue;
            out.add(toMap(p));
        }
        return out;
    }

    /**
     * Catálogo completo para el gestor: títulos + estado de publicación, con una
     * proyección ligera (sin datos_json/sinopsis) y conteos por estado.
     * El filtrado por texto/estado y el orden se hacen en el frontend para no
     * recargar por cada interacción.
     */
    @GetMapping("/catalogo")
    public ResponseEntity<?> catalogo() {
        String modoActivo = telegramModo.modoActual();
        Map<Long, Publicacion> pubPorTitulo = new HashMap<>();
        for (Publicacion p : publicacionRepository.findAllConTitulo()) {
            if (!visibleEnModo(p, modoActivo)) continue;
            pubPorTitulo.put(p.getTitulo().getId(), p);
        }

        // Precarga de conteos de comentarios
        List<Long> pubIds = pubPorTitulo.values().stream()
                .map(Publicacion::getId)
                .toList();
        Map<Long, Long> conteoComentarios = pubIds.isEmpty()
                ? Map.of()
                : comentarioRepository.conteoPorPublicaciones(pubIds);

        // Próxima hora programada por publicación (items activos de la cola).
        Map<Long, String> programado = new HashMap<>();
        for (ColaPublicacion c : colaRepository.findAtivosConPublicacion()) {
            if (c.getProgramadoPara() != null) {
                programado.put(c.getPublicacion().getId(), c.getProgramadoPara().toString());
            }
        }

        // Nombres de los conjuntos usados (para la insignia del catálogo).
        Map<Long, String> nombreAcoplamiento = new HashMap<>();
        List<Long> acopIds = pubPorTitulo.values().stream()
                .map(Publicacion::getAcoplamientoId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!acopIds.isEmpty()) {
            for (var a : acoplamientoRepository.findAllById(acopIds)) {
                nombreAcoplamiento.put(a.getId(), a.getNombre());
            }
        }

        int borradores = 0;
        int publicados = 0;
        int sin = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (TituloRepository.CatalogoFila t : tituloRepository.catalogo()) {
            Publicacion p = pubPorTitulo.get(t.getId());
            String estado = p == null ? "sin" : p.getEstado();
            if ("publicado".equals(estado)) publicados++;
            else if ("sin".equals(estado)) sin++;
            else borradores++;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("publicacion_id", p == null ? null : p.getId());
            m.put("titulo", nombreMostrable(t));
            m.put("tipo", t.getTipo());
            m.put("anio", t.getAnio());
            m.put("generos", t.getGeneros());
            m.put("rating", t.getRating());
            m.put("poster_path", t.getPosterPath());
            m.put("estado", estado);
            m.put("fecha", fechaMostrable(p, t));
            m.put("comentarios_count", p == null ? 0 : conteoComentarios.getOrDefault(p.getId(), 0L));
            m.put("programado_para", p == null ? null : programado.get(p.getId()));
            Long aid = p == null ? null : p.getAcoplamientoId();
            m.put("acoplamiento_id", aid);
            m.put("acoplamiento", aid == null ? null : nombreAcoplamiento.get(aid));
            items.add(m);
        }

        // Por defecto el catálogo se ordena en forma descendente: los más recientes
        // (por fecha de publicación, o de creación si no han salido) primero.
        items.sort((a, b) -> {
            String fa = (String) a.get("fecha");
            String fb = (String) b.get("fecha");
            if (fa == null && fb == null) return 0;
            if (fa == null) return 1;
            if (fb == null) return -1;
            return fb.compareTo(fa);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("borradores", borradores);
        out.put("publicados", publicados);
        out.put("sin", sin);
        out.put("items", items);
        return ResponseEntity.ok(out);
    }

    private String nombreMostrable(TituloRepository.CatalogoFila t) {
        String latino = t.getTituloLatino();
        return latino != null && !latino.isBlank() ? latino : t.getTituloOriginal();
    }

    /**
     * Un post es visible en el modo activo si: es borrador (compartido entre
     * modos, aún no salió a ninguna parte) o está publicado en ese mismo modo.
     * Así los posts de prueba nunca se ven mezclados con los reales.
     */
    private boolean visibleEnModo(Publicacion p, String modoActivo) {
        if ("publicado".equals(p.getEstado())) {
            return modoActivo.equals(p.getModo()) || modoActivo.equals(TelegramModoService.MODO_REAL) && (p.getModo() == null || p.getModo().isBlank());
        }
        return true;
    }

    private String fechaMostrable(Publicacion p, TituloRepository.CatalogoFila t) {
        LocalDateTime f = p != null
                ? (p.getFechaPublicacion() != null ? p.getFechaPublicacion() : p.getCreadoEn())
                : t.getCreadoEn();
        return f == null ? null : f.toString();
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
    public ResponseEntity<?> publicar(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long acoplamientoId = body != null && body.get("acoplamientoId") != null
                ? Long.valueOf(body.get("acoplamientoId").toString()) : null;
        Publicacion p = publicacionRepository.findById(id)
                .orElse(null);
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
        long limite = config.limiteCaption();
        long longitud = p.getCaption() == null ? 0 : p.getCaption().codePointCount(0, p.getCaption().length());
        if (longitud > limite) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    ColaPublicacionService.errorCaptionLargo(longitud, limite).replace("Acortaldo en el editor y presioná Reintentar.", "Acortá el caption antes de publicar.")));
        }
        try {
            List<LinkVideo> links = linkVideoRepository.findByTituloIdOrderByOrdenAsc(p.getTitulo().getId());
            // El conjunto guarda destinos reales y de prueba divididos: solo salen
            // los del modo activo, para que un post de prueba nunca llegue al canal real.
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
            Map<String, Object> resp = null;
            for (String canal : canales) {
                resp = telegram.sendPhoto(p, links, canal);
            }
            p.setEstado("publicado");
            p.setModo(telegramModo.modoActual());
            p.setAcoplamientoId(acoplamientoId);
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

    /**
     * Elimina el mensaje del canal y devuelve el registro a borrador
     * (deshacer la publicación sin perder el caption).
     */
    @PostMapping("/{id}/eliminar-del-canal")
    public ResponseEntity<?> eliminarDelCanal(@PathVariable Long id) {
        Publicacion p = publicacionRepository.findById(id)
                .orElse(null);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"publicado".equals(p.getEstado()) || p.getTelegramMessageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El post no está publicado en el canal todavía."));
        }
        try {
            telegram.borrarPost(p);
            p.setEstado("borrador");
            p.setTelegramMessageId(null);
            p.setTelegramChatId(null);
            p.setFechaPublicacion(null);
            p.setFechaEditado(null);
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
            p.setModo(telegramModo.modoActual());
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
        m.put("modo", p.getModo());
        m.put("acoplamiento_id", p.getAcoplamientoId());
        m.put("telegram_message_id", p.getTelegramMessageId());
        m.put("telegram_chat_id", p.getTelegramChatId());
        m.put("chat_dedicado", Boolean.TRUE);
        m.put("telegram_grupo_chat_id", telegramModo.grupoActivo());
        m.put("comentarios_count", comentarioRepository.countByPublicacionId(p.getId()));
        m.put("fecha_publicacion", p.getFechaPublicacion() == null ? null : p.getFechaPublicacion().toString());
        m.put("fecha_editado", p.getFechaEditado() == null ? null : p.getFechaEditado().toString());
        m.put("fecha_republicado", p.getFechaRepublicado() == null ? null : p.getFechaRepublicado().toString());
        return m;
    }
}