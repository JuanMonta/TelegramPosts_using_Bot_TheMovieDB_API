package api.crow.web;

import api.crow.domain.LinkVideo;
import api.crow.domain.Titulo;
import api.crow.repository.LinkVideoRepository;
import api.crow.repository.TituloRepository;
import api.crow.service.TmdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/titulos")
@RequiredArgsConstructor
public class TituloController {

    private final TituloRepository tituloRepository;
    private final LinkVideoRepository linkVideoRepository;
    private final ObjectMapper objectMapper;
    private final TmdbService tmdbService;

    @GetMapping
    public List<Map<String, Object>> listar() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Titulo t : tituloRepository.findAll()) {
            out.add(TmdbController.toMap(t));
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detalle(@PathVariable Long id) {
        return tituloRepository.findById(id)
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>(TmdbController.toMap(t));
                    m.put("datos", datosLigeros(t.getDatosJson()));
                    m.put("links", linksDe(t.getId()));
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        return tituloRepository.findById(id)
                .map(t -> {
                    tituloRepository.deleteById(id);
                    tmdbService.borrarPoster(t);
                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---- Links / botones del post ----

    record LinkRequest(String texto, String url, @JsonProperty("terabox_filename") String teraboxFilename, Integer orden) {}

    @PostMapping("/{id}/links")
    public ResponseEntity<?> agregarLink(@PathVariable Long id, @RequestBody LinkRequest req) {
        if (req.texto() == null || req.texto().isBlank() || req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "texto y url son obligatorios"));
        }
        return tituloRepository.findById(id)
                .map(t -> {
                    LinkVideo link = new LinkVideo();
                    link.setTitulo(t);
                    link.setTextoBoton(req.texto().trim());
                    link.setUrl(req.url().trim());
                    if (req.teraboxFilename() != null && !req.teraboxFilename().isBlank()) {
                        link.setTeraboxFilename(req.teraboxFilename().trim());
                    }
                    link.setOrden(req.orden() == null ? linkVideoRepository.findByTituloIdOrderByOrdenAsc(id).size() : req.orden());
                    linkVideoRepository.save(link);
                    return ResponseEntity.ok(toMap(link));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/links/{linkId}")
    public ResponseEntity<?> editarLink(@PathVariable Long id, @PathVariable Long linkId, @RequestBody LinkRequest req) {
        return linkVideoRepository.findById(linkId)
                .filter(l -> l.getTitulo().getId().equals(id))
                .map(l -> {
                    if (req.texto() != null && !req.texto().isBlank()) l.setTextoBoton(req.texto().trim());
                    if (req.url() != null && !req.url().isBlank()) l.setUrl(req.url().trim());
                    if (req.teraboxFilename() != null) l.setTeraboxFilename(req.teraboxFilename().isBlank() ? null : req.teraboxFilename().trim());
                    if (req.orden() != null) l.setOrden(req.orden());
                    linkVideoRepository.save(l);
                    return ResponseEntity.ok(toMap(l));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/links/{linkId}")
    public ResponseEntity<?> eliminarLink(@PathVariable Long id, @PathVariable Long linkId) {
        return linkVideoRepository.findById(linkId)
                .filter(l -> l.getTitulo().getId().equals(id))
                .map(l -> {
                    linkVideoRepository.delete(l);
                    return ResponseEntity.noContent().<Map<String, Object>>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private List<Map<String, Object>> linksDe(Long tituloId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LinkVideo l : linkVideoRepository.findByTituloIdOrderByOrdenAsc(tituloId)) {
            out.add(toMap(l));
        }
        return out;
    }

    private Map<String, Object> toMap(LinkVideo l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("texto_boton", l.getTextoBoton());
        m.put("url", l.getUrl());
        m.put("terabox_filename", l.getTeraboxFilename());
        m.put("orden", l.getOrden());
        return m;
    }

    /** Devuelve solo las secciones útiles del payload TMDB: estrenos y plataformas. */
    private Map<String, Object> datosLigeros(String datosJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (datosJson == null || datosJson.isBlank()) return out;
        try {
            JsonNode root = objectMapper.readTree(datosJson);
            JsonNode rd = root.path("release_dates");
            JsonNode wp = root.path("watch_providers");
            if (rd.isArray()) out.put("release_dates", objectMapper.convertValue(rd, new TypeReference<List<Object>>() {}));
            if (wp.isObject()) out.put("watch_providers", objectMapper.convertValue(wp, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception ignored) {
            out.put("error", "datos no parseables");
        }
        return out;
    }
}