package api.crow.service;

import api.crow.config.TmdbProperties;
import api.crow.domain.Setting;
import api.crow.domain.Sugerencia;
import api.crow.domain.Titulo;
import api.crow.repository.SettingRepository;
import api.crow.repository.SugerenciaRepository;
import api.crow.repository.TituloRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corrección ortográfica basada en el dominio: el "diccionario" es el corpus
 * de títulos reales de TMDB (popular + títulos guardados). No se usa un
 * corrector léxico porque los títulos suelen contener palabras inventadas.
 *
 * La API de TMDB solo se consulta bajo demanda (tras un miss real de búsqueda)
 * y respetando un TTL: lo demás trabaja contra la BD.
 */
@Service
@RequiredArgsConstructor
public class SugerenciaService {

    private static final String CLAVE_SEED = "corpus_sembrado_en";

    /** Clásicos que la lista "popular" no suele incluir; se siembran con su mismo TTL. */
    private static final List<String> CLASICOS = List.of(
            "Interstellar", "Inception", "The Godfather", "Pulp Fiction", "The Matrix",
            "Gladiator", "Titanic", "The Shawshank Redemption", "Fight Club",
            "The Dark Knight", "Back to the Future", "Forrest Gump");

    /** Palabras que no aportan al comparar títulos (se ignoran en una variante). */
    private static final java.util.Set<String> STOP = java.util.Set.of(
            "the", "a", "an", "el", "la", "los", "las", "un", "una", "unos", "unas",
            "de", "del", "y", "e", "o", "al", "en", "para");

    private final SugerenciaRepository sugerenciaRepository;
    private final SettingRepository settingRepository;
    private final TituloRepository tituloRepository;
    private final TmdbProperties props;
    private final ConfigService configService;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.themoviedb.org/3")
            .build();

    /**
     * Busca el título del corpus más cercano a la consulta.
     * @return mapa con "encontrado" y, si aplica, los datos del candidato.
     */
    public Map<String, Object> sugerir(String query) {
        String nq = normalizar(query);
        if (nq.isBlank()) return Map.of("encontrado", false);

        ensureCorpus();

        List<Sugerencia> corpus = sugerenciaRepository.findAll();
        MejorCandidato mejor = mejorCandidato(corpus, nq);
        if (mejor == null) return Map.of("encontrado", false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("encontrado", true);
        out.put("tmdb_id", mejor.s.getTmdbId());
        out.put("media_type", mejor.s.getMediaType());
        out.put("titulo", mejor.s.getTitulo());
        out.put("anio", mejor.s.getAnio());
        out.put("poster_path", mejor.s.getPosterPath());
        out.put("distancia", mejor.dist);
        return out;
    }

    /** Guarda un título ya resuelto (los que el usuario realmente guarda) como parte del corpus. */
    public void registrar(ApiLikeTitulo t) {
        if (t.tmdbId == null || t.titulo == null || t.titulo.isBlank()) return;
        upsert(t.tmdbId, t.tipo, t.titulo, t.tituloOriginal, t.anio, t.posterPath, "guardado");
    }

    /** Retroalimenta el corpus con toda la biblioteca guardada (costo 0 de TMDB). Idempotente. */
    public void registrarGuardados() {
        try {
            for (Titulo t : tituloRepository.findAll()) {
                if (t.getTmdbId() == null) continue;
                upsert(t.getTmdbId(), t.getTipo(),
                        t.getTituloLatino() != null && !t.getTituloLatino().isBlank()
                                ? t.getTituloLatino() : t.getTituloOriginal(),
                        t.getTituloOriginal(),
                        t.getAnio() == null ? null : String.valueOf(t.getAnio()),
                        t.getPosterPath(), "guardado");
            }
        } catch (Exception ignore) {
            // el corpus de arranque es opcional; no debe tumbar la app si la BD aún no está lista
        }
    }

    // ---- corpus ----

    /** Siembra una vez por TTL: solo se dispara dentro de la primera sugerencia tras un miss. */
    private void ensureCorpus() {
        LocalDateTime sembrado = settingRepository.findById(CLAVE_SEED)
                .map(Setting::getActualizadoEn).orElse(null);
        long ttlDias = props.sugerenciasTtlDias();
        boolean fresco = sembrado != null && sembrado.isAfter(LocalDateTime.now().minusDays(ttlDias));
        if (fresco) return;

        sembrar("movie", 1);
        sembrar("tv", 1);

        // si la base quedó pobre, una página más (una sola vez por ventana)
        if (sugerenciaRepository.countByOrigen("siembra") < 60) {
            sembrar("movie", 2);
            sembrar("tv", 2);
        }

        // clásicos que la lista "popular" no cubre (Interstellar, etc.), mismo TTL
        sembrarClasicos();

        Setting s = settingRepository.findById(CLAVE_SEED).orElseGet(() -> {
            Setting n = new Setting();
            n.setClave(CLAVE_SEED);
            return n;
        });
        s.setActualizadoEn(LocalDateTime.now());
        settingRepository.save(s);
    }

    private void sembrar(String mediaType, int page) {
        try {
            JsonNode body = restClient.get()
                    .uri(uri -> uri.path("/" + mediaType + "/popular")
                            .queryParam("language", props.language())
                            .queryParam("page", page)
                            .build())
                    .header("Authorization", "Bearer " + configService.tmdbToken())
                    .retrieve()
                    .body(JsonNode.class);
            for (JsonNode r : body.path("results")) {
                int tmdbId = r.path("id").asInt();
                String titulo = r.hasNonNull("title") ? r.path("title").asText()
                        : r.path("name").asText();
                String original = r.hasNonNull("original_title") ? r.path("original_title").asText()
                        : r.path("original_name").asText("");
                String fecha = r.hasNonNull("release_date") ? r.path("release_date").asText()
                        : r.path("first_air_date").asText();
                String anio = fecha.length() >= 4 ? fecha.substring(0, 4) : null;
                upsert(tmdbId, mediaType, titulo, original, anio,
                        r.hasNonNull("poster_path") ? r.path("poster_path").asText() : null,
                        "siembra");
            }
        } catch (Exception e) {
            // un fallo opcional de seeding no debe romper la búsqueda original
        }
    }

    /** Resuelve una lista corta de clásicos via /search/multi (1 request por nombre), mismo TTL. */
    private void sembrarClasicos() {
        for (String nombre : CLASICOS) {
            try {
                JsonNode body = restClient.get()
                        .uri(uri -> uri.path("/search/multi")
                                .queryParam("language", props.language())
                                .queryParam("query", nombre)
                                .queryParam("include_adult", "false")
                                .build())
                        .header("Authorization", "Bearer " + configService.tmdbToken())
                        .retrieve()
                        .body(JsonNode.class);
                for (JsonNode r : body.path("results")) {
                    String tipo = r.path("media_type").asText();
                    if (!tipo.equals("movie") && !tipo.equals("tv")) continue;
                    int tmdbId = r.path("id").asInt();
                    String titulo = r.hasNonNull("title") ? r.path("title").asText()
                            : r.path("name").asText();
                    String original = r.hasNonNull("original_title") ? r.path("original_title").asText()
                            : r.path("original_name").asText("");
                    String fecha = r.hasNonNull("release_date") ? r.path("release_date").asText()
                            : r.path("first_air_date").asText();
                    String anio = fecha.length() >= 4 ? fecha.substring(0, 4) : null;
                    upsert(tmdbId, tipo, titulo, original, anio,
                            r.hasNonNull("poster_path") ? r.path("poster_path").asText() : null,
                            "siembra");
                    break; // solo el mejor resultado por nombre
                }
            } catch (Exception e) {
                // fallo opcional de seeding: no romper
            }
        }
    }

    private void upsert(Integer tmdbId, String mediaType, String titulo, String tituloOriginal,
                        String anio, String posterPath, String origen) {
        Sugerencia s = sugerenciaRepository.findByTmdbIdAndMediaType(tmdbId, mediaType).orElseGet(Sugerencia::new);
        s.setTmdbId(tmdbId);
        s.setMediaType(mediaType);
        s.setTitulo(titulo);
        s.setTituloOriginal(tituloOriginal);
        s.setAnio(anio);
        s.setPosterPath(posterPath);
        s.setOrigen(origen);
        s.setCargadoEn(LocalDateTime.now());
        sugerenciaRepository.save(s);
    }

    // ---- emparejamiento ----

    private MejorCandidato mejorCandidato(List<Sugerencia> corpus, String nq) {
        int maxDist = nq.length() <= 5 ? 1 : 2;
        MejorCandidato mejor = null;
        MejorCandidato segundo = null;
        boolean confiable = nq.length() >= 4;
        for (Sugerencia s : corpus) {
            List<String> variantes = new ArrayList<>();
            String nt = normalizar(s.getTitulo());
            String no = normalizar(s.getTituloOriginal());
            if (!nt.isBlank()) variantes.add(nt);
            if (!no.isBlank()) variantes.add(no);
            if (!nt.isBlank()) variantes.add(sinStop(nt));
            if (!no.isBlank()) variantes.add(sinStop(no));

            int d = Integer.MAX_VALUE;
            for (String v : variantes) {
                if (confiable && (v.contains(nq) || v.startsWith(nq))) {
                    d = 0;
                    break;
                }
                d = Math.min(d, damerau(nq, v));
                if (d == 0) break;
                // emparejamiento parcial por token: "shawshak" pega con "shawshank" dentro del título
                for (String tok : v.split(" ")) {
                    d = Math.min(d, damerau(nq, tok));
                }
            }
            if (d > maxDist) continue;
            if (mejor == null || d < mejor.dist) {
                segundo = mejor;
                mejor = new MejorCandidato(s, d);
            } else if (segundo == null || d < segundo.dist) {
                segundo = new MejorCandidato(s, d);
            }
        }
        if (mejor == null) return null;
        // margen de confianza: el mejor debe ganar por al menos 1 respecto al segundo
        if (segundo != null && segundo.dist <= mejor.dist) return null;
        if (mejor.dist > 0 && segundo != null && mejor.dist == segundo.dist) return null;
        return mejor;
    }

    /** Elimina palabras de relleno (artículos/conectores) de un título ya normalizado. */
    private static String sinStop(String normalizado) {
        StringBuilder sb = new StringBuilder();
        for (String tok : normalizado.split(" ")) {
            if (tok.isBlank() || STOP.contains(tok)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    /** Normaliza para comparar: minúsculas, sin acentos ni puntuación, espacios colapsados. */
    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        n = n.replaceAll("[^\\p{Alnum}]+", " ").trim();
        return n;
    }

    /** Distancia Damerau-Levenshtein (transposiciones incluidas), sensible a minúsculas ya normalizadas. */
    static int damerau(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0) return n;
        if (n == 0) return m;
        int[][] d = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) d[i][0] = i;
        for (int j = 0; j <= n; j++) d[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                d[i][j] = Math.min(d[i - 1][j] + 1,
                        Math.min(d[i][j - 1] + 1, d[i - 1][j - 1] + cost));
                // transposición de caracteres adyacentes
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + cost);
                }
            }
        }
        return d[m][n];
    }

    private record MejorCandidato(Sugerencia s, int dist) {}

    /** DTO mínimo para registrar títulos salvados en el corpus. */
    public record ApiLikeTitulo(Integer tmdbId, String tipo, String titulo, String tituloOriginal,
                               String anio, String posterPath) {
    }
}