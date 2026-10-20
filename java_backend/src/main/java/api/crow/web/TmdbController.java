package api.crow.web;

import api.crow.domain.Titulo;
import api.crow.service.SugerenciaService;
import api.crow.service.TmdbService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tmdb")
@RequiredArgsConstructor
public class TmdbController {

    private final TmdbService tmdb;
    private final SugerenciaService sugerencias;

    @GetMapping("/sugerir")
    public ResponseEntity<?> sugerir(@RequestParam("q") String query) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Parámetro 'q' es obligatorio"));
        }
        Map<String, Object> out = sugerencias.sugerir(query.trim());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String query) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Parámetro 'q' es obligatorio"));
        }
        String q = query.trim();
        try {
            Integer id = null;
            if (q.regionMatches(true, 0, "id:", 0, 3)) {
                // modo explícito: siempre por código (sin ambigüedad con títulos numéricos)
                String resto = q.substring(3).trim();
                if (resto.matches("\\d+") && resto.length() <= 9) {
                    id = Integer.valueOf(resto);
                } else {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Para buscar por id usa 'id:438631' o pega el enlace/slug de TMDB"));
                }
            } else {
                // sin prefijo: solo slug ("438631-dune") o URL de TMDB se toman como código;
                // números puros y nombres van a la búsqueda por nombre.
                id = extraerId(q);
            }
            if (id != null) {
                return ResponseEntity.ok(tmdb.buscarPorId(String.valueOf(id)));
            }
            return ResponseEntity.ok(tmdb.search(q));
        } catch (RestClientException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "No se pudo consultar TMDB. Intentá de nuevo en unos minutos."));
        }
    }

    /**
     * Detecta un código de TMDB sin ambigüedad: solo si el texto trae el slug
     * ("438631-dune") o es el enlace de la página (".../movie/438631-dune").
     * Un número pelado NO cuenta (puede ser un título como "1917").
     */
    static Integer extraerId(String q) {
        if (q == null || q.isBlank()) return null;
        java.util.regex.Matcher url = java.util.regex.Pattern
                .compile("themoviedb\\.org/(?:movie|tv)/(\\d+)").matcher(q);
        if (url.find()) return Integer.parseInt(url.group(1));
        java.util.regex.Matcher slug = java.util.regex.Pattern
                .compile("(\\d+)-[a-zA-Z0-9_-]+").matcher(q);
        if (slug.matches()) return Integer.parseInt(slug.group(1));
        return null;
    }

    /**
     * Busca en TMDB, guarda/refresca en la BD (caché) y devuelve el título.
     * El frontend llama aquí cuando el usuario elige una película/serie de la
     * lista de 'search'.
     */
    /**
     * Pósters y backdrops de una película/serie (para elegir imágenes en el editor).
     */
    @GetMapping("/{tipo}/{id}/imagenes")
    public ResponseEntity<?> imagenes(@PathVariable("tipo") String tipo, @PathVariable("id") int id) {
        try {
            return ResponseEntity.ok(tmdb.imagenes(tipo, id));
        } catch (RestClientException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "No se pudieron cargar las imágenes de TMDB. Intentá de nuevo en unos minutos."));
        }
    }

    @GetMapping("/guardar")
    public ResponseEntity<?> guardar(@RequestParam("tipo") String tipo,
                                     @RequestParam("tmdbId") int tmdbId) {
        if ((!tipo.equals("movie") && !tipo.equals("tv")) || tmdbId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "tipo debe ser movie|tv y tmdbId > 0"));
        }
        try {
            Titulo t = tmdb.fetchOrSave(tipo, tmdbId);
            return ResponseEntity.ok(toMap(t));
        } catch (RestClientException e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "No se pudo guardar el título desde TMDB. Intentá de nuevo en unos minutos."));
        }
    }

    static Map<String, Object> toMap(Titulo t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("tmdb_id", t.getTmdbId());
        m.put("tipo", t.getTipo());
        m.put("titulo_original", t.getTituloOriginal());
        m.put("titulo_latino", t.getTituloLatino());
        m.put("anio", t.getAnio());
        m.put("generos", t.getGeneros());
        m.put("rating", t.getRating());
        m.put("vote_count", t.getVoteCount());
        m.put("sinopsis", t.getSinopsis());
        m.put("poster_path", t.getPosterPath());
        m.put("poster_local_path", t.getPosterLocalPath());
        m.put("ultima_actualizacion", t.getUltimaActualizacion() == null ? null : t.getUltimaActualizacion().toString());
        return m;
    }
}