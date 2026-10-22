package api.crow.service;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * Llamadas de solo-lectura a la Bot API de Telegram (getMe, getChat).
 * Sin dependencias a otros servicios propios: asi se evita el ciclo
 * BotService <-> TelegramPublisher.
 */
@Service
public class TelegramInfoService {

    private static final Duration CONEXION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LECTURA_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.telegram.org")
            .requestFactory(fabrica())
            .build();

    private final tools.jackson.databind.ObjectMapper jackson = new tools.jackson.databind.ObjectMapper();

    private static SimpleClientHttpRequestFactory fabrica() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(CONEXION_TIMEOUT);
        f.setReadTimeout(LECTURA_TIMEOUT);
        return f;
    }

    public JsonNode getMe(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("El token es obligatorio");
        try {
            String respBody = restClient.get()
                    .uri("/bot" + t + "/getMe")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String texto = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException(descripcion(texto));
                    })
                    .body(String.class);
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("Respuesta vacia de Telegram getMe");
            }
            JsonNode resp = jackson.readTree(respBody);
            if (!resp.path("ok").asBoolean(false)) {
                throw new IllegalStateException(resp.path("description").asText("error desconocido de Telegram"));
            }
            return resp.path("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo llamar a Telegram getMe: " + e.getMessage());
        }
    }

    /**
     * Devuelve el nodo "result" de getChat para un chat_id dado.
     * Incluye al menos: id, type (private/group/supergroup/channel), title.
     */
    public JsonNode getChat(String token, String chatId) {
        String t = token == null ? "" : token.trim();
        String c = chatId == null ? "" : chatId.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("El token es obligatorio");
        if (c.isEmpty()) throw new IllegalArgumentException("El chat_id es obligatorio");
        try {
            String respBody = restClient.get()
                    .uri(uriChat(t, "getChat", c))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String texto = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException(descripcion(texto));
                    })
                    .body(String.class);
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("Respuesta vacia de Telegram getChat");
            }
            JsonNode resp = jackson.readTree(respBody);
            if (!resp.path("ok").asBoolean(false)) {
                throw new IllegalStateException(resp.path("description").asText("error desconocido de Telegram"));
            }
            return resp.path("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo llamar a Telegram getChat: " + e.getMessage());
        }
    }

    /**
     * Devuelve el array "result" de getChatAdministrators: cada item trae
     * status (creator/administrator/member/...) y user {id, is_bot, first_name, username}.
     * Solo funciona si el bot está dentro del chat.
     */
    public JsonNode getChatAdministrators(String token, String chatId) {
        String t = token == null ? "" : token.trim();
        String c = chatId == null ? "" : chatId.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("El token es obligatorio");
        if (c.isEmpty()) throw new IllegalArgumentException("El chat_id es obligatorio");
        try {
            String respBody = restClient.get()
                    .uri(uriChat(t, "getChatAdministrators", c))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        String texto = new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        throw new IllegalStateException(descripcion(texto));
                    })
                    .body(String.class);
            if (respBody == null || respBody.isBlank()) {
                throw new IllegalStateException("Respuesta vacia de Telegram getChatAdministrators");
            }
            JsonNode resp = jackson.readTree(respBody);
            if (!resp.path("ok").asBoolean(false)) {
                throw new IllegalStateException(resp.path("description").asText("error desconocido de Telegram"));
            }
            return resp.path("result");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo llamar a Telegram getChatAdministrators: " + e.getMessage());
        }
    }

    /**
     * URI ya codificada. Se pasa como URI (no como String) para que RestClient
     * no vuelva a codificar el % de @usuario y Telegram reciba "%40nombre".
     */
    private java.net.URI uriChat(String token, String metodo, String chatId) {
        String q = java.net.URLEncoder.encode(chatId, java.nio.charset.StandardCharsets.UTF_8);
        return java.net.URI.create("https://api.telegram.org/bot" + token + "/" + metodo + "?chat_id=" + q);
    }

    private String descripcion(String texto) {
        if (texto == null || texto.isBlank()) return "Telegram rechazo la operacion. Revisa el token y los permisos del bot.";
        try {
            JsonNode n = jackson.readTree(texto);
            String d = n.path("description").asText(null);
            return d != null && !d.isBlank() ? d : texto;
        } catch (Exception e) {
            return texto;
        }
    }
}
