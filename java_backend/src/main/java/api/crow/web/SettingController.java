package api.crow.web;

import api.crow.domain.Bot;
import api.crow.domain.Setting;
import api.crow.repository.BotRepository;
import api.crow.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingController {

    public static final String CLAVE_PLANTILLA_CAPTION = "caption_template";
    public static final String CLAVE_BOT_DETECTAR = "bot_detectar";

    private final SettingRepository settingRepository;
    private final BotRepository botRepository;

    /**
     * Plantilla por defecto usada para generar captions nuevos.
     * Placeholders: {title} {year} {rating} {votes} {genres} {synopsis} {providers}
     */
    public static final String PLANTILLA_DEFECTO =
            "{title} ({year})\n" +
            "{rating} · {votes} votos\n" +
            "{genres}\n" +
            "\n" +
            "{synopsis}\n" +
            "\n" +
            "Disponible en: {providers}\n" +
            "\n" +
            "Español Latino.\n" +
            "\n" +
            "Contraseña: TeraMoviesMKVFullHD";

    @GetMapping("/caption-template")
    public ResponseEntity<?> obtenerPlantilla() {
        String valor = settingRepository.findById(CLAVE_PLANTILLA_CAPTION)
                .map(Setting::getValor)
                .orElse(PLANTILLA_DEFECTO);
        return ResponseEntity.ok(Map.of("plantilla", valor, "placeholders", new String[]{"title", "year", "rating", "votes", "genres", "synopsis", "providers"}));
    }

    @PutMapping("/caption-template")
    public ResponseEntity<?> guardarPlantilla(@RequestBody Map<String, String> body) {
        String plantilla = body.get("plantilla");
        if (plantilla == null || plantilla.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "la plantilla no puede estar vacía"));
        }
        Setting s = settingRepository.findById(CLAVE_PLANTILLA_CAPTION).orElseGet(Setting::new);
        s.setClave(CLAVE_PLANTILLA_CAPTION);
        s.setValor(plantilla);
        s.setActualizadoEn(LocalDateTime.now());
        settingRepository.save(s);
        return ResponseEntity.ok(Map.of("plantilla", s.getValor()));
    }

    /** Bot recordado para DETECTAR. Si ya no existe, está inactivo o sin token, se limpia. */
    @GetMapping("/bot-detectar")
    public ResponseEntity<?> obtenerBotDetectar() {
        Setting s = settingRepository.findById(CLAVE_BOT_DETECTAR).orElse(null);
        Long id = s == null ? null : id(s.getValor());
        Bot b = id == null ? null : botRepository.findById(id).orElse(null);
        if (b == null || !Boolean.TRUE.equals(b.getActivo())
                || b.getTokenCifrado() == null || b.getTokenCifrado().length == 0) {
            if (s != null) settingRepository.delete(s);
            return ResponseEntity.ok(respuestaBotDetectar(null, null));
        }
        return ResponseEntity.ok(respuestaBotDetectar(id, b.getNombre()));
    }

    /** Guarda el bot elegido para DETECTAR. bot_id null vuelve a "Elige un bot". */
    @PutMapping("/bot-detectar")
    public ResponseEntity<?> guardarBotDetectar(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("bot_id");
        if (raw == null) {
            settingRepository.findById(CLAVE_BOT_DETECTAR).ifPresent(settingRepository::delete);
            return ResponseEntity.ok(respuestaBotDetectar(null, null));
        }
        Long id = id(raw.toString());
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El bot de detección no es válido"));
        }
        Bot b = botRepository.findById(id).orElse(null);
        if (b == null || !Boolean.TRUE.equals(b.getActivo())
                || b.getTokenCifrado() == null || b.getTokenCifrado().length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Ese bot ya no está disponible. Elige otro para detectar."));
        }
        Setting s = settingRepository.findById(CLAVE_BOT_DETECTAR).orElseGet(Setting::new);
        s.setClave(CLAVE_BOT_DETECTAR);
        s.setValor(String.valueOf(id));
        s.setActualizadoEn(LocalDateTime.now());
        settingRepository.save(s);
        return ResponseEntity.ok(respuestaBotDetectar(id, b.getNombre()));
    }

    private Map<String, Object> respuestaBotDetectar(Long id, String nombre) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bot_id", id);
        out.put("nombre", nombre);
        return out;
    }

    private Long id(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return Long.parseLong(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
