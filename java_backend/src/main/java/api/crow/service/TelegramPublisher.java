package api.crow.service;

import api.crow.config.TelegramProperties;
import api.crow.config.TmdbProperties;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.domain.Titulo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TelegramPublisher {

    private final TelegramProperties telegram;
    private final TmdbProperties tmdb;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .build();

    /**
     * Publica el póster + caption + botones de enlaces en el canal.
     * Devuelve el message_id de Telegram y el chat donde se publicó.
     */
    public Map<String, Object> sendPhoto(Publicacion publicacion, List<LinkVideo> links) {
        String photo = posterUrl(publicacion.getTitulo());
        if (photo == null) {
            throw new IllegalStateException("El título no tiene póster. Cárgalo primero desde TMDB.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", telegram.channelId());
        body.put("photo", photo);
        body.put("caption", captionConLinks(publicacion, links));
        body.put("parse_mode", "HTML");

        JsonNode resp = llamar("sendPhoto", body);
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
            llamar("editMessageCaption", body);
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
                llamar("deleteMessage", del);
            } catch (Exception ignore) {
                // el mensaje ya no existía (borrado manual o el bot perdió acceso): se sigue con el nuevo
            }
        }
        return sendPhoto(publicacion, links);
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

    private JsonNode llamar(String metodo, Map<String, Object> body) {
        if (telegram.botToken() == null || telegram.botToken().isBlank())
            throw new IllegalStateException("TELEGRAM_BOT_TOKEN no configurado. Pégalo en application-local.yaml en app.telegram.bot-token");
        if (telegram.channelId() == null || telegram.channelId().isBlank())
            throw new IllegalStateException("TELEGRAM_CHANNEL_ID no configurado.");
        try {
            String respBody = restClient.post()
                    .uri("/bot" + telegram.botToken() + "/" + metodo)
                    .contentType(MediaType.APPLICATION_JSON)
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

    private String posterUrl(Titulo t) {
        if (t == null || t.getPosterPath() == null) return null;
        return tmdb.imageBaseUrl() + "/" + tmdb.posterSize() + t.getPosterPath();
    }
}