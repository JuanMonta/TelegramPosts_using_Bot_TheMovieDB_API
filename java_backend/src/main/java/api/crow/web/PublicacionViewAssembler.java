package api.crow.web;

import api.crow.domain.ColaPublicacion;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.domain.PublicacionMedia;
import api.crow.repository.AcoplamientoRepository;
import api.crow.repository.ColaPublicacionRepository;
import api.crow.repository.ComentarioRepository;
import api.crow.repository.LinkVideoRepository;
import api.crow.repository.PublicacionMediaRepository;
import api.crow.repository.PublicacionRepository;
import api.crow.repository.TituloRepository;
import api.crow.service.MediaService;
import api.crow.service.TelegramModoService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduce entidades de publicación a las formas de mapa que consume la API.
 * Vive fuera del controlador para que este solo despache HTTP.
 */
@Component
@RequiredArgsConstructor
public class PublicacionViewAssembler {

    public static final String TIPO_TITULO = "titulo";
    public static final String TIPO_PERSONALIZADO = "personalizado";

    private final PublicacionRepository publicacionRepository;
    private final TituloRepository tituloRepository;
    private final LinkVideoRepository linkVideoRepository;
    private final PublicacionMediaRepository mediaRepository;
    private final ComentarioRepository comentarioRepository;
    private final ColaPublicacionRepository colaRepository;
    private final AcoplamientoRepository acoplamientoRepository;
    private final MediaService media;
    private final TelegramModoService telegramModo;

    // ---- listado ----

    public List<Map<String, Object>> listar() {
        String modoActivo = telegramModo.modoActual();
        List<Publicacion> visibles = new ArrayList<>();
        for (Publicacion p : publicacionRepository.findAll()) {
            if (visibleEnModo(p, modoActivo)) visibles.add(p);
        }
        List<Long> ids = visibles.stream().map(Publicacion::getId).toList();
        Map<Long, Long> conteoComentarios = ids.isEmpty()
                ? Map.of()
                : comentarioRepository.conteoPorPublicaciones(ids);
        Map<Long, Long> conteoImagenes = ids.isEmpty()
                ? Map.of()
                : mediaRepository.conteoPorPublicaciones(ids);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Publicacion p : visibles) {
            out.add(toMap(p,
                    conteoComentarios.getOrDefault(p.getId(), 0L),
                    conteoImagenes.getOrDefault(p.getId(), 0L)));
        }
        return out;
    }

    // ---- catálogo ----

    public Map<String, Object> catalogo() {
        String modoActivo = telegramModo.modoActual();
        Map<Long, Publicacion> pubPorTitulo = new HashMap<>();
        for (Publicacion p : publicacionRepository.findAllConTitulo()) {
            if (p.getTitulo() == null) continue;
            if (!visibleEnModo(p, modoActivo)) continue;
            pubPorTitulo.put(p.getTitulo().getId(), p);
        }

        List<Long> pubIds = pubPorTitulo.values().stream()
                .map(Publicacion::getId)
                .toList();
        Map<Long, Long> conteoComentarios = pubIds.isEmpty()
                ? Map.of()
                : comentarioRepository.conteoPorPublicaciones(pubIds);
        Map<Long, Long> conteoImagenes = pubIds.isEmpty()
                ? Map.of()
                : mediaRepository.conteoPorPublicaciones(pubIds);

        Map<Long, String> programado = new HashMap<>();
        for (ColaPublicacion c : colaRepository.findAtivosConPublicacion()) {
            if (c.getProgramadoPara() != null) {
                programado.put(c.getPublicacion().getId(), c.getProgramadoPara().toString());
            }
        }

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
            m.put("personalizado", false);
            m.put("publicacion_id", p == null ? null : p.getId());
            m.put("titulo", nombreMostrable(t));
            m.put("tipo", t.getTipo());
            m.put("anio", t.getAnio());
            m.put("generos", t.getGeneros());
            m.put("rating", t.getRating());
            m.put("poster_path", t.getPosterPath());
            m.put("miniatura_url", null);
            m.put("estado", estado);
            m.put("fecha", fechaMostrable(p, t));
            m.put("comentarios_count", p == null ? 0 : conteoComentarios.getOrDefault(p.getId(), 0L));
            m.put("programado_para", p == null ? null : programado.get(p.getId()));
            m.put("imagenes_count", p == null ? 0 : conteoImagenes.getOrDefault(p.getId(), 0L));
            Long aid = p == null ? null : p.getAcoplamientoId();
            m.put("acoplamiento_id", aid);
            m.put("acoplamiento", aid == null ? null : nombreAcoplamiento.get(aid));
            items.add(m);
        }

        // Posts personalizados (sin TMDB): se muestran en el mismo listado.
        List<Publicacion> personalizados = publicacionRepository.findByTipoOrderByCreadoEnDesc(TIPO_PERSONALIZADO).stream()
                .filter(p -> visibleEnModo(p, modoActivo))
                .toList();
        List<Long> persIds = personalizados.stream().map(Publicacion::getId).toList();
        Map<Long, Long> conteoComentariosPersonalizados = persIds.isEmpty()
                ? Map.of()
                : comentarioRepository.conteoPorPublicaciones(persIds);
        for (Publicacion p : personalizados) {
            if ("publicado".equals(p.getEstado())) publicados++;
            else borradores++;

            List<PublicacionMedia> med = mediaRepository.findByPublicacionIdOrderByOrdenAscIdAsc(p.getId());
            String miniatura = med.isEmpty() || med.get(0).getArchivo() == null
                    ? null : "/api/media/" + med.get(0).getArchivo();
            Long aid = p.getAcoplamientoId();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", -p.getId());
            m.put("personalizado", true);
            m.put("publicacion_id", p.getId());
            m.put("titulo", nombreDe(p));
            m.put("tipo", TIPO_PERSONALIZADO);
            m.put("anio", null);
            m.put("generos", null);
            m.put("rating", null);
            m.put("poster_path", null);
            m.put("miniatura_url", miniatura);
            m.put("estado", p.getEstado());
            m.put("fecha", p.getFechaPublicacion() != null
                    ? p.getFechaPublicacion().toString()
                    : (p.getCreadoEn() == null ? null : p.getCreadoEn().toString()));
            m.put("comentarios_count", conteoComentariosPersonalizados.getOrDefault(p.getId(), 0L));
            m.put("programado_para", programado.get(p.getId()));
            m.put("imagenes_count", med.size());
            m.put("acoplamiento_id", aid);
            m.put("acoplamiento", aid == null ? null : nombreAcoplamiento.get(aid));
            items.add(m);
        }

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
        return out;
    }

    // ---- helpers de presentación ----

    public String nombreMostrable(TituloRepository.CatalogoFila t) {
        String latino = t.getTituloLatino();
        return latino != null && !latino.isBlank() ? latino : t.getTituloOriginal();
    }

    public boolean visibleEnModo(Publicacion p, String modoActivo) {
        return p.getModo() == null
                || "borrador".equals(p.getEstado())
                || p.getModo().equals(modoActivo);
    }

    public String fechaMostrable(Publicacion p, TituloRepository.CatalogoFila t) {
        LocalDateTime f = p != null
                ? (p.getFechaPublicacion() != null ? p.getFechaPublicacion() : p.getCreadoEn())
                : t.getCreadoEn();
        return f == null ? null : f.toString();
    }

    public boolean perteneceA(LinkVideo l, Long id) {
        return l.getPublicacionId() != null && l.getPublicacionId().equals(id);
    }

    public Map<String, Object> linkToMap(LinkVideo l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("texto_boton", l.getTextoBoton());
        m.put("url", l.getUrl());
        m.put("terabox_filename", l.getTeraboxFilename());
        m.put("orden", l.getOrden());
        return m;
    }

    public List<PublicacionMedia> mediaDe(Publicacion p) {
        return mediaRepository.findByPublicacionIdOrderByOrdenAscIdAsc(p.getId());
    }

    public List<Resource> recursosDe(List<PublicacionMedia> med) {
        List<Resource> out = new ArrayList<>();
        for (PublicacionMedia m : med) {
            if (m.getArchivo() != null) out.add(media.recurso(m.getArchivo()));
        }
        return out;
    }

    public List<LinkVideo> linksDe(Publicacion p) {
        if (p.getTitulo() != null) {
            return linkVideoRepository.findByTituloIdOrderByOrdenAsc(p.getTitulo().getId());
        }
        return linkVideoRepository.findByPublicacionIdOrderByOrdenAsc(p.getId());
    }

    /** Nombre visible del post: título de TMDB o la primera línea del caption. */
    public String nombreDe(Publicacion p) {
        if (p.getTitulo() != null) {
            String latino = p.getTitulo().getTituloLatino();
            return latino != null && !latino.isBlank() ? latino : p.getTitulo().getTituloOriginal();
        }
        if (p.getCaption() != null) {
            for (String linea : p.getCaption().split("\\r?\\n")) {
                String t = linea.trim();
                if (!t.isEmpty()) return t.length() > 100 ? t.substring(0, 100) + "…" : t;
            }
        }
        return "Post personalizado";
    }

    public Map<String, Object> toMap(Publicacion p) {
        return toMap(p,
                comentarioRepository.countByPublicacionId(p.getId()),
                mediaRepository.countByPublicacionId(p.getId()));
    }

    public Map<String, Object> toMap(Publicacion p, long comentariosCount, long imagenesCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("titulo_id", p.getTitulo() == null ? null : p.getTitulo().getId());
        m.put("titulo", nombreDe(p));
        m.put("tipo", p.getTipo() == null ? TIPO_TITULO : p.getTipo());
        m.put("personalizado", TIPO_PERSONALIZADO.equals(p.getTipo()));
        m.put("caption", p.getCaption());
        m.put("estado", p.getEstado());
        m.put("modo", p.getModo());
        m.put("acoplamiento_id", p.getAcoplamientoId());
        m.put("telegram_message_id", p.getTelegramMessageId());
        m.put("telegram_chat_id", p.getTelegramChatId());
        m.put("chat_dedicado", Boolean.TRUE);
        m.put("telegram_grupo_chat_id", telegramModo.grupoActivo());
        m.put("comentarios_count", comentariosCount);
        m.put("imagenes_count", imagenesCount);
        m.put("fecha_publicacion", p.getFechaPublicacion() == null ? null : p.getFechaPublicacion().toString());
        m.put("fecha_editado", p.getFechaEditado() == null ? null : p.getFechaEditado().toString());
        m.put("fecha_republicado", p.getFechaRepublicado() == null ? null : p.getFechaRepublicado().toString());
        return m;
    }
}
