package api.crow.service;

import api.crow.config.TmdbProperties;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.domain.Titulo;
import api.crow.domain.Canal;
import api.crow.repository.BotRepository;
import api.crow.repository.CanalRepository;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

    private final TmdbProperties tmdb;
    private final TelegramModoService modo;
    private final ConfigService config;
    private final BotRepository botRepository;
    private final CanalRepository canalRepository;
    private final ConfigCifradoService cifrado;
    private final TelegramInfoService info;
    private final tools.jackson.databind.ObjectMapper jackson = new tools.jackson.databind.ObjectMapper();

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
     * Publica el p??ster + caption + botones de enlaces en el canal activo
     * (seg??n el modo real/prueba actual).
     */
    public Map<String, Object> sendPhoto(Publicacion publicacion, List<LinkVideo> links) {
        return sendPhoto(publicacion, links, modo.canalActivo(), null);
    }

    /**
     * Publica el p??ster + caption + botones de enlaces en un canal expl??cito.
     * Lo usa la cola de publicaciones para respetar el modo en que se program??
     * el post aunque despu??s se cambie el modo desde el frontend.
     * El p??ster se sube como ARCHIVO (multipart), no como URL: Telegram no tiene
     * que ir a descargarlo del CDN de TMDB, lo que eliminaba cuelgues de 20s+
     * cuando el CDN iba lento (y el consiguiente error de conexi??n en el frontend).
     * Devuelve el message_id de Telegram y el chat donde se public??.
     */
    public Map<String, Object> sendPhoto(Publicacion publicacion, List<LinkVideo> links, String canal) {
        return sendPhoto(publicacion, links, canal, null);
    }

    public Map<String, Object> sendPhoto(Publicacion publicacion, List<LinkVideo> links, String canal, Long botId) {
        if (botId == null && canal != null && !canal.isBlank()) {
            botId = canalRepository.findAll().stream()
                    .filter(c -> canal.equals(c.getChatId()))
                    .map(Canal::getBotId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        }
        Resource foto = posterImagen(publicacion.getTitulo());

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", canal);
        form.add("photo", foto);
        form.add("caption", captionConLinks(publicacion, links));
        form.add("parse_mode", "HTML");

        JsonNode resp = llamar("sendPhoto", form, MediaType.MULTIPART_FORM_DATA, canal, botId);
        return parseMensaje(resp);
    }

    /**
     * Publica un post en el canal: si no hay imágenes va como mensaje de texto,
     * con una sola imagen como foto y con 2 a 10 como álbum (sendMediaGroup).
     * Los posts de un título (sin imágenes propias) usan su póster como única foto.
     */
    public Map<String, Object> enviarPost(Publicacion publicacion, List<LinkVideo> links,
                                          List<Resource> fotos, String canal) {
        return enviarPost(publicacion, links, fotos, canal, null);
    }

    public Map<String, Object> enviarPost(Publicacion publicacion, List<LinkVideo> links,
                                          List<Resource> fotos, String canal, Long botId) {
        if (botId == null && canal != null && !canal.isBlank()) {
            botId = botIdParaCanal(canal);
        }
        List<Resource> efectivas = new ArrayList<>();
        if (fotos != null) efectivas.addAll(fotos);
        if (efectivas.isEmpty() && publicacion.getTitulo() != null) {
            efectivas.add(posterImagen(publicacion.getTitulo()));
        }

        String caption = captionConLinks(publicacion, links);
        if (efectivas.isEmpty()) {
            return enviarTexto(canal, caption, botId);
        }
        if (efectivas.size() == 1) {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("chat_id", canal);
            form.add("photo", efectivas.get(0));
            form.add("caption", caption);
            form.add("parse_mode", "HTML");
            return parseMensaje(llamar("sendPhoto", form, MediaType.MULTIPART_FORM_DATA, canal, botId));
        }

        // Álbum: hasta 10 imágenes; el caption va en la primera.
        int n = Math.min(efectivas.size(), 10);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", canal);
        ArrayNode media = jackson.createArrayNode();
        for (int i = 0; i < n; i++) {
            ObjectNode item = jackson.createObjectNode();
            item.put("type", "photo");
            item.put("media", "attach://file" + i);
            if (i == 0) {
                item.put("caption", caption);
                item.put("parse_mode", "HTML");
            }
            media.add(item);
            form.add("file" + i, efectivas.get(i));
        }
        form.add("media", media.toString());
        JsonNode result = llamar("sendMediaGroup", form, MediaType.MULTIPART_FORM_DATA, canal, botId);
        if (!result.isArray() || result.isEmpty()) {
            throw new IllegalStateException("Telegram no devolvió el álbum publicado.");
        }
        return parseMensaje(result.get(0));
    }

    private Map<String, Object> enviarTexto(String canal, String texto, Long botId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", canal);
        body.put("text", texto);
        body.put("parse_mode", "HTML");
        return parseMensaje(llamar("sendMessage", body, MediaType.APPLICATION_JSON, canal, botId));
    }

    private Map<String, Object> parseMensaje(JsonNode msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message_id", msg.path("message_id").asLong());
        out.put("chat_id", msg.path("chat").path("id").asLong());
        return out;
    }

    private Long botIdParaCanal(String canal) {
        return canalRepository.findAll().stream()
                .filter(c -> canal.equals(c.getChatId()))
                .map(Canal::getBotId)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    /**
     * Edita el post original en el canal con la informaci??n actual (caption + botones),
     * sin repostear. La foto no cambia (el p??ster del t??tulo es fijo).
     * Si el contenido ya es id??ntico al del mensaje, Telegram responde
     * "message is not modified" y se trata como una edici??n no-op exitosa.
     */
    public void editarPost(Publicacion publicacion, List<LinkVideo> links, boolean conMedia) {
        if (publicacion.getTelegramMessageId() == null
                || publicacion.getTelegramChatId() == null
                || publicacion.getTelegramChatId().isBlank()) {
            throw new IllegalStateException("El post no tiene message_id/chat_id registrados. Debe estar publicado.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", publicacion.getTelegramChatId());
        body.put("message_id", publicacion.getTelegramMessageId());
        body.put(conMedia ? "caption" : "text", captionConLinks(publicacion, links));
        body.put("parse_mode", "HTML");

        try {
            llamar(conMedia ? "editMessageCaption" : "editMessageText",
                    body, MediaType.APPLICATION_JSON, publicacion.getTelegramChatId(), null);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                return; // el post ya ten??a exactamente este contenido: no-op exitoso
            }
            throw e;
        }
    }

    /**
     * Elimina el post original del canal (si existe) y publica uno nuevo
     * con la informaci??n actual. Devuelve los datos del mensaje nuevo.
     */
    public Map<String, Object> republicar(Publicacion publicacion, List<LinkVideo> links, List<Resource> fotos) {
        if (publicacion.getTelegramMessageId() != null
                && publicacion.getTelegramChatId() != null
                && !publicacion.getTelegramChatId().isBlank()) {
            try {
                borrarPost(publicacion);
            } catch (Exception ignore) {
                // el mensaje ya no exist??a (borrado manual o el bot perdi?? acceso): se sigue con el nuevo
            }
        }
        return enviarPost(publicacion, links, fotos, modo.canalActivo());
    }

    /** Elimina el mensaje del canal usando el message_id/chat_id registrados. */
    public void borrarPost(Publicacion publicacion) {
        if (publicacion.getTelegramMessageId() == null
                || publicacion.getTelegramChatId() == null
                || publicacion.getTelegramChatId().isBlank()) {
            throw new IllegalStateException("El post no tiene message_id/chat_id registrados. Debe estar publicado.");
        }
        Map<String, Object> del = new LinkedHashMap<>();
        del.put("chat_id", publicacion.getTelegramChatId());
        del.put("message_id", publicacion.getTelegramMessageId());
        llamar("deleteMessage", del, MediaType.APPLICATION_JSON, publicacion.getTelegramChatId(), null);
    }

    // ---- p??ster como archivo ----

    /** Foto para subir a Telegram: el archivo local cachead si existe; si no, lo descarga de TMDB. */
    private Resource posterImagen(Titulo t) {
        if (t == null || t.getPosterPath() == null || t.getPosterPath().isBlank()) {
            throw new IllegalStateException("El t??tulo no tiene p??ster. C??rgalo primero desde TMDB.");
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
            // no hay cach?? local: se descarga desde TMDB
        }
        return null;
    }

    private Resource descargarYCachear(Titulo t) {
        try {
            String url = tmdb.imageBaseUrl() + "/" + tmdb.posterSize() + t.getPosterPath();
            byte[] img = posterClient.get().uri(url).retrieve().body(byte[].class);
            if (img == null || img.length == 0) {
                throw new IllegalStateException("P??ster vac??o para tmdbId=" + t.getTmdbId());
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
            throw new IllegalStateException("No se pudo descargar el p??ster desde TMDB: " + e.getMessage());
        }
    }

    /** Guarda el p??ster en disco para no volver a descargarlo. Cach?? opcional: falla silencioso. */
    private void cachearLocal(Titulo t, byte[] img) {
        try {
            Path dir = Path.of(tmdb.posterLocalDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Files.write(dir.resolve(t.getTmdbId() + ".jpg"), img);
        } catch (Exception ignore) {
            // cach?? opcional: si falla, la pr??xima vez se descarga de nuevo
        }
    }

    /**
     * Caption final del post: el caption del borrador + los links como texto
     * HTML al final ("???? <a href=...>etiqueta</a>"). No se usan botones inline:
     * Telegram desactiva el bot??n nativo "Dejar un comentario" cuando el post
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
                sb.append("???? <a href=\"").append(escapeHtml(l.getUrl())).append("\">")
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

    private String obtenerToken(Long botId) {
        if (botId == null) return config.tokenBot();
        try {
            return botRepository.findById(botId)
                    .map(b -> cifrado.descifrar(b.getTokenCifrado()))
                    .filter(t -> t != null && !t.isBlank())
                    .orElseGet(() -> config.tokenBot());
        } catch (Exception e) {
            return config.tokenBot();
        }
    }

    /** Token efectivo para un chat: respeta el bot asignado al canal si existe. */
    public String tokenParaChat(String chatId) {
        if (chatId == null || chatId.isBlank()) return config.tokenBot();
        try {
            Long botId = canalRepository.findAll().stream()
                    .filter(c -> chatId.equals(c.getChatId()))
                    .map(Canal::getBotId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
            return obtenerToken(botId);
        } catch (Exception e) {
            return config.tokenBot();
        }
    }
    /** Llama a la Bot API con el body indicado (JSON o multipart seg??n content-type). */
    private JsonNode llamar(String metodo, Object body, MediaType contentType, String canal, Long botId) {
        String token = obtenerToken(botId);
        if (token == null || token.isBlank())
            throw new IllegalStateException("El token del bot no est?? configurado. Cargalo en Configuraci??n (o en TELEGRAM_BOT_TOKEN).");
        if (token == null || token.isBlank())
            throw new IllegalStateException("El token del bot no est?? configurado. Cargalo en Configuraci??n (o en TELEGRAM_BOT_TOKEN).");
        if ((canal == null || canal.isBlank()) && (modo.canalActivo() == null || modo.canalActivo().isBlank()))
            throw new IllegalStateException("El canal de publicaci??n no est?? configurado. Cargalo en Configuraci??n (o TELEGRAM_CHANNEL_ID).");
        try {
            String respBody = restClient.post()
                    .uri("/bot" + token + "/" + metodo)
                    .contentType(contentType)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String texto = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException(descripcion(texto));
                    })
                    .body(String.class);
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("Respuesta vac??a de Telegram");
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
     * Obtiene actualizaciones del grupo de discusi??n (long-poll).
     * Devuelve el nodo "result" del JSON de Telegram (array de updates).
     * offset: update_id m??s alto procesado (Telegram no devuelve updates ya le??dos).
     * timeout: espera server-side en segundos (0 = inmediato, 50 = long-poll est??ndar).
     * Usa pollClient porque el read timeout debe cubrir el bloqueo del long-poll.
     */
    public JsonNode getUpdates(long offset, int timeout) {
        String token = config.tokenBot();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("El token del bot no est?? configurado. Cargalo en Configuraci??n.");
        }
        try {
            String uri = "/bot" + token + "/getUpdates?offset=" + offset
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
                throw new IllegalStateException("Respuesta vac??a de Telegram getUpdates");
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

    private String descripcion(String texto) {
        if (texto == null || texto.isBlank()) return "Telegram rechaz? la operaci?n. Revisa BOT_TOKEN y los permisos del bot.";
        try {
            JsonNode n = jackson.readTree(texto);
            String d = n.path("description").asText(null);
            if (d != null && !d.isBlank()) return d.replaceAll("\\s+", " ").trim();
        } catch (Exception ignore) {
            // no es JSON: se usa el texto plano
        }
        // Una sola línea: el cuerpo crudo de Telegram no debe ensuciar logs ni respuestas.
        return texto.replaceAll("\\s+", " ").trim();
    }
    /** Obtiene informacion del bot (getMe). Delega a TelegramInfoService. */
    public JsonNode getMe(String token) {
        return info.getMe(token);
    }

    /** Obtiene info del chat (getChat): type=private/group/supergroup/channel + title. */
    public JsonNode getChat(String token, String chatId) {
        return info.getChat(token, chatId);
    }
    }
