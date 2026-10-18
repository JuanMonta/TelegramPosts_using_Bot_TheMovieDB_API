package api.crow.service;

import api.crow.domain.Comentario;
import api.crow.domain.Publicacion;
import api.crow.domain.Setting;
import api.crow.repository.ComentarioRepository;
import api.crow.repository.PublicacionRepository;
import api.crow.repository.SettingRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Long-polling del grupo de discusión vinculado al canal.
 * Un único hilo daemon llama a getUpdates con timeout=50: Telegram mantiene la
 * conexión abierta hasta que llega una actualización (o expira el timeout), así
 * la API solo se toca cuando hay algo que procesar y no se satura con polling
 * vacío. Procesa los mensajes que son replies a posts del canal (comentarios).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommentPoller {

    private static final String CLAVE_OFFSET = "ultimo_update_id";
    private static final int TIMEOUT_LONG_POLL = 50;

    private final TelegramPublisher telegram;
    private final PublicacionRepository publicacionRepository;
    private final ComentarioRepository comentarioRepository;
    private final SettingRepository settingRepository;
    private final TelegramModoService modo;
    private final ConfigService config;

    private Thread hilo;
    private volatile boolean activo;

    @PostConstruct
    void arrancar() {
        // El hilo arranca siempre: si hoy no hay token/grupo configurados espera y
        // reintentan cada 30s, así el polling se activa solo al cargarlos en
        // Configuración, sin reiniciar el backend.
        activo = true;
        hilo = new Thread(this::bucle, "comment-poller");
        hilo.setDaemon(true);
        hilo.start();
        log.info("CommentPoller: long-polling iniciado (timeout={}s)", TIMEOUT_LONG_POLL);
    }

    @PreDestroy
    void detener() {
        activo = false;
        if (hilo != null) {
            hilo.interrupt();
        }
    }

    private void bucle() {
        long offset = leerOffset();
        while (activo) {
            try {
                if (config.tokenBot() == null || config.tokenBot().isBlank()
                        || modo.grupoActivo() == null || modo.grupoActivo().isBlank()) {
                    // Sin token/grupo todavía: esperar a que se configuren (hot).
                    Thread.sleep(30_000);
                    continue;
                }
                JsonNode updates = telegram.getUpdates(offset, TIMEOUT_LONG_POLL);
                if (updates != null && updates.isArray()) {
                    for (JsonNode update : updates) {
                        long updateId = update.path("update_id").asLong();
                        procesarUpdate(update);
                        if (updateId >= offset) {
                            offset = updateId + 1;
                        }
                    }
                }
                guardarOffset(offset - 1);
            } catch (Exception e) {
                // error transitorio (timeout, red, parada): se reanuda enseguida el long-poll
                log.debug("CommentPoller: {}", e.getMessage());
                if (!activo || Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void procesarUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode()) return;

        long chatId = message.path("chat").path("id").asLong();
        Long discussionGroupId = parseLongOrNull(modo.grupoActivo());
        if (discussionGroupId == null || chatId != discussionGroupId) return;

        JsonNode replyTo = message.path("reply_to_message");
        if (replyTo.isMissingNode()) return;

        long channelMessageId = replyTo.path("message_id").asLong();
        Optional<Publicacion> pubOpt = publicacionRepository
                .findByTelegramChatIdAndTelegramMessageId(modo.canalActivo(), channelMessageId);
        if (pubOpt.isEmpty()) return;

        long telegramMsgId = message.path("message_id").asLong();
        if (comentarioRepository.existsByTelegramMessageId(telegramMsgId)) return;

        String texto = message.path("text").asText(null);
        String autor = extraerAutor(message);
        LocalDateTime fecha = extraerFecha(message);

        Comentario c = new Comentario();
        c.setPublicacionId(pubOpt.get().getId());
        c.setTelegramMessageId(telegramMsgId);
        c.setAutor(autor);
        c.setTexto(texto);
        c.setFecha(fecha);
        comentarioRepository.save(c);

        log.debug("Comentario guardado: pub={}, autor={}, texto={}", pubOpt.get().getId(), autor, texto);
    }

    private String extraerAutor(JsonNode message) {
        JsonNode from = message.path("from");
        if (from.isMissingNode()) return "Anónimo";
        String username = from.path("username").asText(null);
        if (username != null && !username.isBlank()) return "@" + username;
        String firstName = from.path("first_name").asText("");
        String lastName = from.path("last_name").asText("");
        String nombre = (firstName + " " + lastName).trim();
        return nombre.isEmpty() ? "Anónimo" : nombre;
    }

    private LocalDateTime extraerFecha(JsonNode message) {
        long date = message.path("date").asLong(0);
        if (date == 0) return LocalDateTime.now();
        return Instant.ofEpochSecond(date).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private long leerOffset() {
        try {
            Optional<Setting> s = settingRepository.findById(CLAVE_OFFSET);
            if (s.isEmpty()) return 0;
            try {
                return Long.parseLong(s.get().getValor());
            } catch (NumberFormatException e) {
                return 0;
            }
        } catch (Exception e) {
            // Base de datos todavía no creada (primer arranque): empezar de cero.
            return 0;
        }
    }

    /** Persiste el offset como update_id del último update procesado. */
    private void guardarOffset(long ultimoUpdateId) {
        long nuevo = Math.max(ultimoUpdateId + 1, 1);
        Optional<Setting> s = settingRepository.findById(CLAVE_OFFSET);
        if (s.isPresent()) {
            s.get().setValor(String.valueOf(nuevo));
            s.get().setActualizadoEn(LocalDateTime.now());
            settingRepository.save(s.get());
        } else {
            Setting setting = new Setting();
            setting.setClave(CLAVE_OFFSET);
            setting.setValor(String.valueOf(nuevo));
            setting.setActualizadoEn(LocalDateTime.now());
            settingRepository.save(setting);
        }
    }

    private Long parseLongOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}