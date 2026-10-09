package api.crow.service;

import api.crow.config.TelegramProperties;
import api.crow.config.TmdbProperties;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.domain.Titulo;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TelegramPublisher {

    private static final Duration CONEXION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LECTURA_TIMEOUT = Duration.ofSeconds(45);
    /** Long-poll de getUpdates: puede bloquear hasta 50s server-side, el read timeout lo cubre holgado. */
    private static final Duration LECTURA_POLL_TIMEOUT = Duration.ofSeconds(70);

    private final TelegramProperties telegram;
    private final TmdbProperties tmdb;
    private final TelegramModoService modo;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .requestFactory(fabricaConTimeouts())
            .build();

    /** Cliente solo para getUpdates: read timeout ampliado por el long-poll. */
    private final RestClient pollClient = RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .requestFactory(fabricaConTimeoutsPoll())
            .build();

    private final RestClient posterClient = RestClient.builder()
            .requestFactory(fabricaConTimeouts())
            .build();

    private static SimpleClientHttpRequestFactory fabricaConTimeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONEXION_TIMEOUT);
        f.setReadTimeout(LECTURA_TIMEOUT);
        return f;
    }

    private static SimpleClientHttpRequestFactory fabricaConTimeoutsPoll() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONEXION_TIMEOUT);
        f.setReadTimeout(LECTURA_POLL_TIMEOUT);
        return f;
    }

    /**
     * Publica el póster + caption + botones de enlaces en el canal.
     * El póster se sube como ARCHIVO (multipart), no como URL: Telegram no tiene
     * que ir a descargarlo del CDN de TMDB, lo que eliminaba cuelgues de 20s+
     * cuando el CDN iba lento (y el consiguiente error de conexión en el frontend).
     * Devuelve el message_id de Telegram y el chat donde se publicó.
     */
    public Map<String, Object> sendPhoto(Publicacion publicacion, List<LinkVideo> links) {
        Resource foto = posterImagen(publicacion.getTitulo());

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", modo.canalActivo());
        form.add("photo", foto);
        form.add("caption", captionConLinks(publicacion, links));
        form.add("parse_mode", "HTML");

        JsonNode resp = llamar("sendPhoto", form, MediaType.MULTIPART_FORM_DATA);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message_id", resp.path("message_id").asLong());
        out.put("chat_id", resp.path("chat").path("id").asLong());
        return out;
    }

    /**
     * Edita el post original en el canal con la información actual (caption + botones),
     * sin repostear. La foto no cambia (el póster del título es fijo).
     * Si el contenido ya es idéntico al del mensaje, Telegram responde
     * "message is not modified" y se trata como una edición no-op exitosa.
     */
    public void editarPost(Publicacion publicacion, List<LinkVideo> links) {
        if (publicacion.getTelegramMessageId() == null
                || publicacion.getTelegramChatId() == null
                || publicacion.getTelegramChatId().isBlank()) {
            throw new IllegalStateException("El post no tiene message_id/chat_id registrados. Debe estar publicado.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", publicacion.getTelegramChatId());
        body.put("message_id", publicacion.getTelegramMessageId());
        body.put("caption", captionConLinks(publicacion, links));
        body.put("parse_mode", "HTML");

        try {
            llamar("editMessageCaption", body, MediaType.APPLICATION_JSON);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                return; // el post ya tenía exactamente este contenido: no-op exitoso
            }
            throw e;
        }
    }

    /**
     * Elimina el post original del canal (si existe) y publica uno nuevo
     * con la información actual. Devuelve los datos del mensaje nuevo.
     */
    public Map<String, Object> republicar(Publicacion publicacion, List<LinkVideo> links) {
        if (publicacion.getTelegramMessageId() != null
                && publicacion.getTelegramChatId() != null
                && !publicacion.getTelegramChatId().isBlank()) {
            Map<String, Object> del = new LinkedHashMap<>();
            del.put("chat_id", publicacion.getTelegramChatId());
            del.put("message_id", publicacion.getTelegramMessageId());
            try {
                llamar("deleteMessage", del, MediaType.APPLICATION_JSON);
            } catch (Exception ignore) {
                // el mensaje ya no existía (borrado manual o el bot perdió acceso): se sigue con el nuevo
            }
        }
        return sendPhoto(publicacion, links);
    }

    // ---- póster como archivo ----

    /** Foto para subir a Telegram: el archivo local cachead si existe; si no, lo descarga de TMDB. */
    private Resource posterImagen(Titulo t) {
        if (t == null || t.getPosterPath() == null || t.getPosterPath().isBlank()) {
            throw new IllegalStateException("El título no tiene póster. Cárgalo primero desde TMDB.");
        }
        Resource local = posterLocal(t);
        if (local != null) return local;
        return descargarYCachear(t);
    }

    private Resource posterLocal(Titulo t) {
        if (t.getPosterLocalPath() == null || t.getPosterLocalPath().isBlank()) return null;
        try {
            Path p = Path.of(t.getPosterLocalPath()).toAbsolutePath().normalize();
            if (Files.exists(p) && Files.isRegularFile(p)) return new FileSystemResource(p);
        } catch (Exception ignore) {
            // no hay caché local: se descarga desde TMDB
        }
        return null;
    }

    private Resource descargarYCachear(Titulo t) {
        try {
            String url = tmdb.imageBaseUrl() + "/" + tmdb.posterSize() + t.getPosterPath();
            byte[] img = posterClient.get().uri(url).retrieve().body(byte[].class);
            if (img == null || img.length == 0) {
                throw new IllegalStateException("Póster vacío para tmdbId=" + t.getTmdbId());
            }
            cachearLocal(t, img);
            return new ByteArrayResource(img) {
                @Override
                public String getFilename() {
                    return t.getTmdbId() + ".jpg";
                }
            };
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descargar el póster desde TMDB: " + e.getMessage());
        }
    }

    /** Guarda el póster en disco para no volver a descargarlo. Caché opcional: falla silencioso. */
    private void cachearLocal(Titulo t, byte[] img) {
        try {
            Path dir = Path.of(tmdb.posterLocalDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Files.write(dir.resolve(t.getTmdbId() + ".jpg"), img);
        } catch (Exception ignore) {
            // caché opcional: si falla, la próxima vez se descarga de nuevo
        }
    }

    /**
     * Caption final del post: el caption del borrador + los links como texto
     * HTML al final ("📥 <a href=...>etiqueta</a>"). No se usan botones inline:
     * Telegram desactiva el botón nativo "Dejar un comentario" cuando el post
     * lleva botones, por eso los links van directo en el texto del post.
     */
    private String captionConLinks(Publicacion publicacion, List<LinkVideo> links) {
        StringBuilder sb = new StringBuilder();
        if (publicacion.getCaption() != null && !publicacion.getCaption().isBlank()) {
            sb.append(escapeHtml(publicacion.getCaption().trim()));
        }
        if (links != null) {
            for (LinkVideo l : links) {
                if (sb.length() > 0) sb.append("\n\n");
                String texto = l.getTextoBoton() == null || l.getTextoBoton().isBlank() ? "Link" : l.getTextoBoton();
                sb.append("📥 <a href=\"").append(escapeHtml(l.getUrl())).append("\">")
                        .append(escapeHtml(texto)).append("</a>");
            }
        }
        return sb.toString();
    }

    /** Escapa los caracteres reservados de HTML para que el caption se muestre textual. */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private final tools.jackson.databind.ObjectMapper jackson = new tools.jackson.databind.ObjectMapper();

    /** Llama a la Bot API con el body indicado (JSON o multipart según content-type). */
    private JsonNode llamar(String metodo, Object body, MediaType contentType) {
        if (telegram.botToken() == null || telegram.botToken().isBlank())
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN no configurado. Pégalo en application-local.yaml en app.telegram.bot-token");
        if (modo.canalActivo() == null || modo.canalActivo().isBlank())
            throw new IllegalStateException("TELEGRAM_CHANNEL_ID no configurado.");
        try {
            String respBody = restClient.post()
                    .uri("/bot" + telegram.botToken() + "/" + metodo)
                    .contentType(contentType)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String texto = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException(descripcion(texto));
                    })
                    .body(String.class);
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("Respuesta vacía de Telegram");
            }
            JsonNode resp = jackson.readTree(respBody);
            if (!resp.path("ok").asBoolean(false)) {
                throw new IllegalStateException(resp.path("description").asText("error desconocido de Telegram"));
            }
            return resp.path("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo llamar a Telegram: " + e.getMessage());
        }
    }

    /**
     * Obtiene actualizaciones del grupo de discusión (long-poll).
     * Devuelve el nodo "result" del JSON de Telegram (array de updates).
     * offset: update_id más alto procesado (Telegram no devuelve updates ya leídos).
     * timeout: espera server-side en segundos (0 = inmediato, 50 = long-poll estándar).
     * Usa pollClient porque el read timeout debe cubrir el bloqueo del long-poll.
     */
    public JsonNode getUpdates(long offset, int timeout) {
        if (telegram.botToken() == null || telegram.botToken().isBlank()) {
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN no configurado.");
        }
        try {
            String uri = "/bot" + telegram.botToken() + "/getUpdates?offset=" + offset
                    + "&timeout=" + timeout
                    + "&allowed_updates=[\"message\"]";
            String respBody = pollClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String texto = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException(descripcion(texto));
                    })
                    .body(String.class);
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("Respuesta vacía de Telegram getUpdates");
            }
            JsonNode resp = jackson.readTree(respBody);
            if (!resp.path("ok").asBoolean(false)) {
                throw new IllegalStateException(resp.path("description").asText("error desconocido de Telegram"));
            }
            return resp.path("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo llamar a Telegram getUpdates: " + e.getMessage());
        }
    }

    /** Extrae la descripción legible de un body de error de Telegram (fallback: el texto crudo). */
    private String descripcion(String texto) {
        if (texto == null || texto.isBlank()) return "Telegram rechazó la operación. Revisa BOT_TOKEN y los permisos del bot.";
        try {
            JsonNode n = jackson.readTree(texto);
            String d = n.path("description").asText(null);
            return d != null && !d.isBlank() ? d : texto;
        } catch (Exception e) {
            return texto;
        }
    }
}