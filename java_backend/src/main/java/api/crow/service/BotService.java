package api.crow.service;

import api.crow.domain.Bot;
import api.crow.repository.BotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BotService {

    private final BotRepository repo;
    private final ConfigCifradoService cifrado;
    private final ConfigService config;
    private final TelegramInfoService telegramInfo;

    public List<Map<String, Object>> listar() {
        return repo.findAll().stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> crear(String nombre, String token) {
        String t = validarToken(token);
        String nombreFinal = resolverNombre(nombre, t);
        validarNombreMin(nombreFinal);
        Bot b = new Bot();
        b.setNombre(nombreFinal.trim());
        b.setTokenCifrado(cifrado.cifrar(t));
        b.setActivo(true);
        completarDatosTelegram(b, t);
        validarDuplicado(b, null);
        return toMap(repo.save(b));
    }

    @Transactional
    public Map<String, Object> actualizar(Long id, String nombre, String token) {
        Bot b = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El bot no existe"));
        if (nombre != null && !nombre.isBlank()) {
            validarNombre(nombre);
            b.setNombre(nombre.trim());
        }
        if (token != null && !token.isBlank()) {
            String t = validarToken(token);
            b.setTokenCifrado(cifrado.cifrar(t));
            completarDatosTelegram(b, t);
            validarDuplicado(b, id);
        }
        return toMap(repo.save(b));
    }

    @Transactional
    public void eliminar(Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("El bot no existe");
        }
        repo.deleteById(id);
    }

    private void validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank() || nombre.trim().length() > 100) {
            throw new IllegalArgumentException("El nombre es obligatorio (máx. 100 caracteres)");
        }
    }

    private String validarToken(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("El token es obligatorio");
        return t;
    }

    public String tokenBot(Long botId) {
        if (botId == null) return config.tokenBot();
        return repo.findById(botId).map(b -> cifrado.descifrar(b.getTokenCifrado())).orElse(config.tokenBot());
    }

    private Map<String, Object> toMap(Bot b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("nombre", b.getNombre());
        String tok = cifrado.descifrar(b.getTokenCifrado());
        m.put("token", ConfigService.enmascarar(tok));
        m.put("token_configurado", tok != null && !tok.isBlank());
        m.put("telegram_id", b.getTelegramId());
        m.put("username", b.getUsername());
        m.put("activo", b.getActivo());
        return m;
    }
    private String resolverNombre(String nombre, String token) {
        String n = nombre == null ? "" : nombre.trim();
        if (n.length() >= 3) return n;
        try {
            tools.jackson.databind.JsonNode botInfo = telegramInfo.getMe(token);
            String firstName = botInfo.path("first_name").asText("");
            String username = botInfo.path("username").asText("");
            if (!firstName.isBlank()) return firstName;
            if (!username.isBlank()) return username;
        } catch (Exception e) {
            // ignorar
        }
        return n;
    }

    private void validarNombreMin(String nombre) {
        String n = nombre == null ? "" : nombre.trim();
        if (n.length() < 3 || n.length() > 100) {
            throw new IllegalArgumentException("El nombre del bot debe tener entre 3 y 100 caracteres");
        }
    }

    /**
     * Guarda el ID y @username de Telegram (getMe) para poder cruzar este bot
     * con los administradores detectados en un canal/grupo. Mejor esfuerzo:
     * si Telegram no responde, el bot igual se guarda sin esos datos.
     */
    private void completarDatosTelegram(Bot b, String tokenPlano) {
        try {
            tools.jackson.databind.JsonNode me = telegramInfo.getMe(tokenPlano);
            long tid = me.path("id").asLong(0);
            if (tid != 0) b.setTelegramId(tid);
            String un = me.path("username").asText("");
            if (!un.isBlank()) b.setUsername(un.trim());
        } catch (Exception ignore) {
            // sin red no hay cruce: el bot igual queda registrado
        }
    }

    /**
     * Evita registrar dos veces el mismo bot de Telegram aunque le pongas otro
     * nombre personalizado: el ID numérico no cambia aunque renombres el bot o
     * su @username en BotFather. Si ya existe, se avisa con qué nombre está
     * guardado para que lo edites en vez de duplicarlo.
     */
    private void validarDuplicado(Bot b, Long exceptoId) {
        if (b.getTelegramId() != null) {
            for (Bot otro : repo.findAll()) {
                if (exceptoId != null && exceptoId.equals(otro.getId())) continue;
                if (b.getTelegramId().equals(otro.getTelegramId())) {
                    throw new IllegalArgumentException("Ese bot ya está registrado como '" + otro.getNombre()
                            + "'. Edítalo en la pestaña Bots si quieres cambiarle el nombre.");
                }
            }
        }
        if (b.getUsername() != null && !b.getUsername().isBlank()) {
            String un = b.getUsername().trim();
            for (Bot otro : repo.findAll()) {
                if (exceptoId != null && exceptoId.equals(otro.getId())) continue;
                if (otro.getUsername() != null && un.equalsIgnoreCase(otro.getUsername().trim())
                        && (otro.getTelegramId() == null || !otro.getTelegramId().equals(b.getTelegramId()))) {
                    throw new IllegalArgumentException("El @username @" + un + " ya está registrado como '"
                            + otro.getNombre() + "'. Edítalo en la pestaña Bots si quieres cambiarle el nombre.");
                }
            }
        }
    }
}
