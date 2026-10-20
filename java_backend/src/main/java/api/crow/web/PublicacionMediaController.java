package api.crow.web;

import api.crow.domain.Publicacion;
import api.crow.domain.PublicacionMedia;
import api.crow.repository.PublicacionMediaRepository;
import api.crow.repository.PublicacionRepository;
import api.crow.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imágenes de un post: listar, subir una propia, adjuntar una de TMDB,
 * borrar y reordenar. El procesado/guardado lo hace MediaService.
 */
@RestController
@RequestMapping("/api/publicaciones/{id}/media")
@RequiredArgsConstructor
public class PublicacionMediaController {

    /** Telegram admite hasta 10 elementos por álbum. */
    private static final int MAX_IMAGENES = 10;

    private final PublicacionRepository publicaciones;
    private final PublicacionMediaRepository mediaRepo;
    private final MediaService media;

    record TmdbReq(String tipo, String path) {}

    record OrdenReq(List<Long> ids) {}

    @GetMapping
    public ResponseEntity<?> listar(@PathVariable Long id) {
        if (!publicaciones.existsById(id)) return ResponseEntity.notFound().build();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicacionMedia m : mediaRepo.findByPublicacionIdOrderByOrdenAscIdAsc(id)) {
            out.add(toMap(m));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/subir")
    public ResponseEntity<?> subir(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws IOException {
        Publicacion p = publicaciones.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        if (mediaRepo.countByPublicacionId(id) >= MAX_IMAGENES) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Ya hay " + MAX_IMAGENES + " imágenes en este post (el máximo de Telegram)."));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No se recibió ningún archivo."));
        }
        MediaService.Resultado r = media.guardarSubida(file.getBytes());
        PublicacionMedia m = new PublicacionMedia();
        m.setPublicacionId(id);
        m.setOrden(siguienteOrden(id));
        m.setArchivo(r.archivo());
        m.setOrigen(PublicacionMedia.ORIGEN_SUBIDA);
        m.setAncho(r.ancho());
        m.setAlto(r.alto());
        m.setBytes(r.bytes());
        mediaRepo.save(m);
        return ResponseEntity.ok(toMap(m));
    }

    @PostMapping("/tmdb")
    public ResponseEntity<?> adjuntarTmdb(@PathVariable Long id, @RequestBody TmdbReq req) {
        if (!publicaciones.existsById(id)) return ResponseEntity.notFound().build();
        if (mediaRepo.countByPublicacionId(id) >= MAX_IMAGENES) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Ya hay " + MAX_IMAGENES + " imágenes en este post (el máximo de Telegram)."));
        }
        MediaService.Resultado r = media.guardarDesdeTmdb(req.path());
        PublicacionMedia m = new PublicacionMedia();
        m.setPublicacionId(id);
        m.setOrden(siguienteOrden(id));
        m.setArchivo(r.archivo());
        m.setOrigen(PublicacionMedia.ORIGEN_TMDB);
        m.setTmdbTipo(req.tipo());
        m.setTmdbPath(req.path());
        m.setAncho(r.ancho());
        m.setAlto(r.alto());
        m.setBytes(r.bytes());
        mediaRepo.save(m);
        return ResponseEntity.ok(toMap(m));
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<?> borrar(@PathVariable Long id, @PathVariable Long mediaId) {
        return mediaRepo.findById(mediaId)
                .filter(m -> m.getPublicacionId().equals(id))
                .map(m -> {
                    media.borrar(m.getArchivo());
                    mediaRepo.delete(m);
                    return ResponseEntity.noContent().<Map<String, Object>>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/orden")
    public ResponseEntity<?> reordenar(@PathVariable Long id, @RequestBody OrdenReq req) {
        if (!publicaciones.existsById(id)) return ResponseEntity.notFound().build();
        if (req.ids() == null || req.ids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Falta el orden de las imágenes."));
        }
        List<PublicacionMedia> lista = mediaRepo.findByPublicacionIdOrderByOrdenAscIdAsc(id);
        int orden = 0;
        for (Long mid : req.ids()) {
            for (PublicacionMedia m : lista) {
                if (m.getId().equals(mid)) {
                    m.setOrden(orden++);
                    mediaRepo.save(m);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PublicacionMedia m : mediaRepo.findByPublicacionIdOrderByOrdenAscIdAsc(id)) {
            out.add(toMap(m));
        }
        return ResponseEntity.ok(out);
    }

    private int siguienteOrden(Long id) {
        List<PublicacionMedia> lista = mediaRepo.findByPublicacionIdOrderByOrdenAscIdAsc(id);
        return lista.isEmpty() ? 0 : lista.get(lista.size() - 1).getOrden() + 1;
    }

    static Map<String, Object> toMap(PublicacionMedia m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("publicacion_id", m.getPublicacionId());
        out.put("orden", m.getOrden());
        out.put("archivo", m.getArchivo());
        out.put("url", m.getArchivo() == null ? null : "/api/media/" + m.getArchivo());
        out.put("origen", m.getOrigen());
        out.put("tmdb_tipo", m.getTmdbTipo());
        out.put("tmdb_path", m.getTmdbPath());
        out.put("ancho", m.getAncho());
        out.put("alto", m.getAlto());
        out.put("bytes", m.getBytes());
        return out;
    }
}
