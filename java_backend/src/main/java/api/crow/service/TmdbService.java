package api.crow.service;

import api.crow.config.TmdbProperties;
import api.crow.domain.Titulo;
import api.crow.repository.TituloRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TmdbService {

    private final TmdbProperties props;
    private final TituloRepository tituloRepository;
    private final ObjectMapper objectMapper;
    private final SugerenciaService sugerenciaService;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.themoviedb.org/3")
            .build();

    /**
     * Búsqueda en TMDB (multi: películas + series) para el selector del frontend.
     */
    public List<Map<String, Object>> search(String query) {
        checkKey();
        JsonNode body = restClient.get()
                .uri(uri -> uri.path("/search/multi")
                        .queryParam("language", props.language())
                        .queryParam("query", query)
                        .queryParam("include_adult", "false")
                        .build())
                .header("Authorization", "Bearer " + props.apiKey())
                .retrieve()
                .body(JsonNode.class);

        List<Map<String, Object>> hits = new ArrayList<>();
        for (JsonNode r : body.path("results")) {
            String tipo = r.path("media_type").asText();
            if (!tipo.equals("movie") && !tipo.equals("tv")) continue;
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", r.path("id").asInt());
            hit.put("media_type", tipo);
            hit.put("titulo", r.hasNonNull("title") ? r.path("title").asText() : r.path("name").asText());
            String fecha = r.hasNonNull("release_date") ? r.path("release_date").asText()
                    : r.path("first_air_date").asText();
            hit.put("anio", fecha.length() >= 4 ? fecha.substring(0, 4) : "");
            hit.put("poster_path", r.hasNonNull("poster_path") ? r.path("poster_path").asText() : null);
            hit.put("overview", r.path("overview").asText(""));
            hits.add(hit);
        }
        return hits;
    }

    /**
     * Búsqueda por id: resuelve el código oficial de TMDB (intenta movie, luego tv)
     * y devuelve un hit con la misma forma que search(), o lista vacía si no existe.
     */
    public List<Map<String, Object>> buscarPorId(String idStr) {
        int tmdbId;
        try {
            tmdbId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return List.of();
        }
        Map<String, Object> hit = toHitPorId("movie", tmdbId);
        if (hit == null) hit = toHitPorId("tv", tmdbId);
        return hit == null ? List.of() : List.of(hit);
    }

    private Map<String, Object> toHitPorId(String tipo, int tmdbId) {
        checkKey();
        JsonNode d = getJsonOrNull(tipo + "/" + tmdbId);
        if (d == null) return null;
        boolean esTv = tipo.equals("tv");
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("id", tmdbId);
        hit.put("media_type", tipo);
        hit.put("titulo", d.path(esTv ? "name" : "title").asText(""));
        String fecha = d.path(esTv ? "first_air_date" : "release_date").asText("");
        hit.put("anio", fecha.length() >= 4 ? fecha.substring(0, 4) : "");
        hit.put("poster_path", d.path("poster_path").asText(null));
        hit.put("overview", d.path("overview").asText(""));
        return hit;
    }

    /** Como getJson pero devuelve null ante HTTP 404 (lo usan las búsquedas por id). */
    private JsonNode getJsonOrNull(String path) {
        try {
            return getJson(path);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) return null;
            throw e;
        }
    }

    /**
     * Devuelve el título guardado (caché). Si no existe o pasó el TTL, consulta
     * TMDB (details + release_dates + watch/providers LATAM) y lo guarda/refresca.
     */
    public Titulo fetchOrSave(String tipo, int tmdbId) {
        Optional<Titulo> existente = tituloRepository.findByTmdbId(tmdbId);
        if (existente.isPresent()) {
            Titulo t = existente.get();
            if (isFresh(t)) return t;
            return refresh(t);
        }

        checkKey();
        Titulo titulo = new Titulo();
        titulo.setTipo(tipo);
        titulo.setTmdbId(tmdbId);
        return refresh(titulo);
    }

    private Titulo refresh(Titulo titulo) {
        String suffix = titulo.getTipo().equals("tv") ? "tv" : "movie";
        JsonNode details = getJson(suffix + "/" + titulo.getTmdbId());
        JsonNode releaseDates = getJson(suffix + "/" + titulo.getTmdbId() + "/release_dates");
        JsonNode providers = getJson(suffix + "/" + titulo.getTmdbId() + "/watch/providers");

        boolean esTv = titulo.getTipo().equals("tv");
        titulo.setTituloOriginal(details.path(esTv ? "original_name" : "original_title").asText(""));
        titulo.setTituloLatino(details.path(esTv ? "name" : "title").asText(""));
        titulo.setSinopsis(details.path("overview").asText(""));
        titulo.setRating(details.hasNonNull("vote_average")
                ? BigDecimal.valueOf(details.path("vote_average").asDouble()) : null);
        titulo.setVoteCount(details.hasNonNull("vote_count") ? details.path("vote_count").asInt() : null);
        titulo.setPosterPath(details.path("poster_path").asText(null));
        titulo.setBackdropPath(details.path("backdrop_path").asText(null));
        titulo.setGeneros(joinNames(details.path("genres")));

        String fecha = details.path(esTv ? "first_air_date" : "release_date").asText("");
        titulo.setAnio(fecha.length() >= 4 ? Integer.parseInt(fecha.substring(0, 4)) : null);

        ObjectNode cache = objectMapper.createObjectNode();
        cache.set("details", details);
        cache.set("release_dates", releaseDates);
        cache.set("watch_providers", providers);
        titulo.setDatosJson(cache.toString());
        titulo.setUltimaActualizacion(LocalDateTime.now());

        tituloRepository.save(titulo);
        sugerenciaService.registrar(new SugerenciaService.ApiLikeTitulo(
                titulo.getTmdbId(), titulo.getTipo(),
                titulo.getTituloLatino() != null && !titulo.getTituloLatino().isBlank()
                        ? titulo.getTituloLatino() : titulo.getTituloOriginal(),
                titulo.getTituloOriginal(),
                titulo.getAnio() == null ? null : String.valueOf(titulo.getAnio()),
                titulo.getPosterPath()));
        downloadPoster(titulo);
        return titulo;
    }

    private boolean isFresh(Titulo t) {
        if (t.getUltimaActualizacion() == null) return false;
        return t.getUltimaActualizacion().isAfter(LocalDateTime.now().minusDays(props.cacheTtlDays()));
    }

    private JsonNode getJson(String path) {
        String ruta = path.startsWith("/") ? path : "/" + path;
        return restClient.get()
                .uri("https://api.themoviedb.org/3" + ruta + "?language=" + props.language())
                .header("Authorization", "Bearer " + props.apiKey())
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 200 || status == 206) {
                        try (InputStream in = response.getBody()) {
                            return objectMapper.readTree(in);
                        }
                    }
                    String cuerpo = response.getBody() == null ? "" : new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    throw new RuntimeException("TMDB " + ruta + " [" + request.getURI() + "] -> HTTP " + status + (cuerpo.isBlank() ? "" : ": " + cuerpo));
                });
    }

    private String joinNames(JsonNode arr) {
        List<String> names = new ArrayList<>();
        arr.forEach(n -> names.add(n.path("name").asText()));
        return names.isEmpty() ? null : String.join(", ", names);
    }

    /** Descarga el póster a disco (evita depender de TMDB para el envío a Telegram). */
    public void downloadPoster(Titulo titulo) {
        if (titulo.getPosterPath() == null || titulo.getPosterPath().isBlank()) return;
        try {
            byte[] img = restClient.get()
                    .uri(props.imageBaseUrl() + props.posterSize() + titulo.getPosterPath())
                    .retrieve()
                    .body(byte[].class);
            Path dir = Path.of(props.posterLocalDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(titulo.getTmdbId() + ".jpg");
            Files.write(file, img);
            titulo.setPosterLocalPath(props.posterLocalDir() + "/" + titulo.getTmdbId() + ".jpg");
            tituloRepository.save(titulo);
        } catch (Exception e) {
            // Nunca debe fallar la petición de guardado por la descarga del póster:
            // el envío a Telegram usa la URL de TMDB directamente, así que esto es
            // solo una caché opcional. Cualquier error del CDN (4xx/5xx, timeout,
            // I/O) se ignora y se sigue con el flujo.
        }
    }

    public String posterUrl(Integer tmdbId, String posterPath) {
        if (posterPath == null) return null;
        return props.imageBaseUrl() + props.posterSize() + posterPath;
    }

    /**
     * Borra el póster local en disco del título (si existe).
     * El path se construye a partir del directorio configurado + tmdbId (nunca del
     * string guardado en BD), así el archivo siempre queda dentro de storage/posters
     * y no hay riesgo de path traversal. No debe romper el flujo de eliminación del
     * título: cualquier error se registra y se continúa.
     */
    public void borrarPoster(Titulo titulo) {
        if (titulo == null || titulo.getTmdbId() == null) return;
        try {
            Path dir = Path.of(props.posterLocalDir()).toAbsolutePath().normalize();
            Path file = dir.resolve(titulo.getTmdbId() + ".jpg").normalize();
            if (Files.deleteIfExists(file)) {
                log.info("Póster local eliminado: {}", file);
            }
        } catch (Exception e) {
            log.warn("No se pudo borrar el póster local del tmdbId={}: {}", titulo.getTmdbId(), e.getMessage());
        }
    }

    private void checkKey() {
        if (props.apiKey() == null || props.apiKey().isBlank())
            throw new IllegalStateException("TMDB_API_KEY no configurada. Pégalo en application-local.yaml en app.tmdb.api-key");
    }
}