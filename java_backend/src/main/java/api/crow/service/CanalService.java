package api.crow.service;

import api.crow.domain.Bot;
import api.crow.domain.Canal;
import api.crow.repository.BotRepository;
import api.crow.repository.CanalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CanalService {

    private final CanalRepository repo;
    private final BotRepository bots;
    private final ConfigCifradoService cifrado;
    private final TelegramInfoService info;

    public List<Map<String, Object>> listar() {
        return repo.findAll().stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> crear(String nombre, String chatId, String tipo, Long botId, String chatTipo) {
        String idChat = validarChatId(chatId);
        String tip = validarTipo(tipo);
        if (repo.existsByChatIdAndTipo(idChat, tip)) {
            throw new IllegalArgumentException("Ya existe un canal con ese chat_id en " + tip);
        }
        String nombreFinal = resolverNombre(nombre, idChat, botId);
        validarNombreMin(nombreFinal);
        Canal c = new Canal();
        c.setNombre(nombreFinal.trim());
        c.setChatId(idChat);
        c.setTipo(tip);
        c.setBotId(botId);
        String ct = chatTipo == null || chatTipo.isBlank() ? detectarTipoSilencioso(idChat, botId) : chatTipo.trim().toLowerCase();
        c.setChatTipo(normalizarChatTipo(ct));
        c.setActivo(true);
        return toMap(repo.save(c));
    }

    @Transactional
    public Map<String, Object> crear(String nombre, String chatId, String tipo, Long botId) {
        return crear(nombre, chatId, tipo, botId, null);
    }

    @Transactional
    public Map<String, Object> actualizar(Long id, String nombre, String chatId, String tipo, Boolean activo, Long botId, String chatTipo) {
        Canal c = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El canal no existe"));
        if (nombre != null && !nombre.isBlank()) validarNombre(nombre);
        boolean cambioChat = false;
        if (chatId != null && !chatId.isBlank()) {
            String idChat = validarChatId(chatId);
            if (!idChat.equals(c.getChatId())) cambioChat = true;
            c.setChatId(idChat);
        }
        if (tipo != null && !tipo.isBlank()) {
            c.setTipo(validarTipo(tipo));
        }
        if (nombre != null && !nombre.isBlank()) c.setNombre(nombre.trim());
        if (activo != null) c.setActivo(activo);
        if (botId != null) {
            c.setBotId(botId);
            cambioChat = true;
        }
        if (chatTipo != null && !chatTipo.isBlank()) {
            c.setChatTipo(normalizarChatTipo(chatTipo));
        } else if (cambioChat) {
            String det = detectarTipoSilencioso(c.getChatId(), c.getBotId());
            if (det != null) c.setChatTipo(normalizarChatTipo(det));
        }
        return toMap(repo.save(c));
    }

    @Transactional
    public Map<String, Object> actualizar(Long id, String nombre, String chatId, String tipo, Boolean activo, Long botId) {
        return actualizar(id, nombre, chatId, tipo, activo, botId, null);
    }

    /**
     * Detecta via getChat (API de Telegram) y devuelve mapa con type/title para la UI.
     * Usa SOLO el bot elegido en el desplegable: no hay token por defecto,
     * todo se configura a mano en la UI. Lanza si Telegram falla.
     */
    public Map<String, Object> detectar(String chatId, Long botId) {
        String idChat = validarChatId(chatId);
        if (botId == null) {
            throw new IllegalArgumentException("Elige un bot en el desplegable para detectar. Sin bot no hay con qué preguntarle a Telegram.");
        }
        String token = tokenPara(botId);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("El bot elegido no tiene token legible. Revísalo en la pestaña Bots.");
        }
        tools.jackson.databind.JsonNode r = info.getChat(token, idChat);
        Map<String, Object> m = new LinkedHashMap<>();
        String type = r.path("type").asText("");
        m.put("type", type);
        m.put("chat_tipo", normalizarChatTipo(type));
        m.put("etiqueta", etiquetaChatTipo(type));
        m.put("title", r.path("title").asText(""));
        m.put("username", r.path("username").asText(""));
        m.put("id", r.path("id").asLong());
        // Bots administradores del chat (para ofrecer registrarlos). No es fatal:
        // falla si el bot elegido no está dentro del canal/grupo.
        // Cada uno se cruza con los bots ya registrados (por ID y por @username).
        Map<Long, Bot> porTelegramId = new java.util.HashMap<>();
        Map<String, Bot> porUsername = new java.util.HashMap<>();
        for (Bot reg : bots.findAll()) {
            completarDatosTelegramFaltantes(reg);
            if (reg.getTelegramId() != null) porTelegramId.put(reg.getTelegramId(), reg);
            if (reg.getUsername() != null && !reg.getUsername().isBlank()) {
                porUsername.put(reg.getUsername().trim().toLowerCase(), reg);
            }
        }
        List<Map<String, Object>> adminBots = new java.util.ArrayList<>();
        try {
            tools.jackson.databind.JsonNode admins = info.getChatAdministrators(token, idChat);
            if (admins != null && admins.isArray()) {
                for (tools.jackson.databind.JsonNode a : admins) {
                    tools.jackson.databind.JsonNode u = a.path("user");
                    if (u.path("is_bot").asBoolean(false)) {
                        long tid = u.path("id").asLong(0);
                        String un = u.path("username").asText("").trim();
                        Bot reg = tid != 0 ? porTelegramId.get(tid) : null;
                        if (reg == null && !un.isBlank()) reg = porUsername.get(un.toLowerCase());
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("id", tid);
                        b.put("nombre", u.path("first_name").asText(""));
                        b.put("username", un);
                        b.put("estado", a.path("status").asText(""));
                        b.put("registrado", reg != null);
                        b.put("bot_id", reg == null ? null : reg.getId());
                        b.put("bot_nombre", reg == null ? null : reg.getNombre());
                        adminBots.add(b);
                    }
                }
            }
        } catch (Exception e) {
            m.put("admin_error", "No se pudo leer administradores (" + e.getMessage()
                    + "). El bot elegido debe estar dentro del canal/grupo.");
        }
        m.put("admin_bots", adminBots);
        return m;
    }

    /**
     * Rellena ID/@username de un bot viejo (registrado antes de guardar esos
     * datos) y refresca el @username si cambió en BotFather (el ID no cambia
     * nunca). Mejor esfuerzo y sin romper la detección si Telegram no responde.
     */
    private void completarDatosTelegramFaltantes(Bot reg) {
        try {
            String t = cifrado.descifrar(reg.getTokenCifrado());
            if (t == null || t.isBlank()) return;
            tools.jackson.databind.JsonNode me = info.getMe(t);
            long tid = me.path("id").asLong(0);
            boolean cambio = false;
            if (tid != 0 && !Long.valueOf(tid).equals(reg.getTelegramId())) {
                reg.setTelegramId(tid);
                cambio = true;
            }
            String un = me.path("username").asText("");
            if (!un.isBlank() && !un.trim().equals(reg.getUsername())) {
                reg.setUsername(un.trim());
                cambio = true;
            }
            if (cambio) bots.save(reg);
        } catch (Exception ignore) {
            // sin red no hay cruce para este bot
        }
    }

    @Transactional
    public void eliminar(Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("El canal no existe");
        }
        repo.deleteById(id);
    }

    /** Token del bot elegido. Sin bot no hay token: no existe token por defecto. */
    private String tokenPara(Long botId) {
        if (botId == null) return null;
        try {
            String t = bots.findById(botId).map(b -> cifrado.descifrar(b.getTokenCifrado())).orElse(null);
            return (t != null && !t.isBlank()) ? t : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private String detectarTipoSilencioso(String chatId, Long botId) {
        try {
            String token = tokenPara(botId);
            if (token == null || token.isBlank()) return null;
            tools.jackson.databind.JsonNode r = info.getChat(token, chatId);
            String t = r.path("type").asText(null);
            return (t == null || t.isBlank()) ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizarChatTipo(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        return switch (t) {
            case "channel", "canal" -> "channel";
            case "group", "grupo" -> "group";
            case "supergroup", "supergrupo", "supergrup" -> "supergroup";
            case "private", "privado" -> "private";
            default -> t.isEmpty() ? null : t;
        };
    }

    public static String etiquetaChatTipo(String s) {
        if (s == null) return "SIN DETECTAR";
        return switch (s.trim().toLowerCase()) {
            case "channel", "canal" -> "CANAL";
            case "group", "grupo" -> "GRUPO";
            case "supergroup", "supergrupo", "supergrup" -> "SUPERGRUPO";
            case "private", "privado" -> "PRIVADO";
            default -> s.toUpperCase();
        };
    }

    private void validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank() || nombre.trim().length() > 100) {
            throw new IllegalArgumentException("El nombre es obligatorio (max. 100 caracteres)");
        }
    }

    /**
     * Si el nombre viene vacio o con menos de 3 letras y hay un bot elegido,
     * se usa el titulo real del chat (getChat de Telegram). Sin bot no hay
     * autocompletado: el error de abajo pide escribirlo o elegir un bot.
     */
    private String resolverNombre(String nombre, String chatId, Long botId) {
        String n = nombre == null ? "" : nombre.trim();
        if (n.length() >= 3) return n;
        try {
            String token = tokenPara(botId);
            if (token != null && !token.isBlank()) {
                tools.jackson.databind.JsonNode r = info.getChat(token, chatId);
                String title = r.path("title").asText("");
                if (!title.isBlank()) return title.trim();
                String username = r.path("username").asText("");
                if (!username.isBlank()) return username.trim();
            }
        } catch (Exception ignore) {
            // si falla getChat, se valida abajo y da el error amigable
        }
        return n;
    }

    private void validarNombreMin(String nombre) {
        String n = nombre == null ? "" : nombre.trim();
        if (n.length() < 3 || n.length() > 100) {
            throw new IllegalArgumentException("El nombre debe tener entre 3 y 100 letras. Escríbelo o elige un bot y toca DETECTAR para autocompletarlo.");
        }
    }

    private String validarChatId(String chatId) {
        String s = chatId == null ? "" : chatId.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("El chat_id es obligatorio");
        if (!s.startsWith("@") && !s.matches("-?\\d{5,25}")) {
            throw new IllegalArgumentException("El chat_id debe ser @nombredecanal o un id numerico (ej. -1001234567890)");
        }
        return s;
    }

    private String validarTipo(String tipo) {
        String s = tipo == null ? "" : tipo.trim().toLowerCase();
        if (!"real".equals(s) && !"test".equals(s)) {
            throw new IllegalArgumentException("El tipo debe ser 'real' o 'test'");
        }
        return s;
    }

    private Map<String, Object> toMap(Canal c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("nombre", c.getNombre());
        m.put("chat_id", c.getChatId());
        m.put("tipo", c.getTipo());
        m.put("bot_id", c.getBotId());
        m.put("chat_tipo", c.getChatTipo());
        m.put("chat_etiqueta", etiquetaChatTipo(c.getChatTipo()));
        m.put("activo", c.getActivo());
        return m;
    }
}
