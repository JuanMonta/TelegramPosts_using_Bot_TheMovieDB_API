package api.crow.web;

import api.crow.config.TelegramProperties;
import api.crow.service.TelegramModoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Estado del modo real/prueba y cuáles son el canal/grupo activos. */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final TelegramModoService modo;
    private final TelegramProperties props;

    record ModoRequest(String modo) {}

    @GetMapping
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modo", modo.modoActual());
        out.put("test_configurado", props.tieneTest());
        out.put("canal_activo", modo.canalActivo());
        out.put("grupo_activo", modo.grupoActivo());
        out.put("canal_real", props.channelId());
        out.put("grupo_real", props.discussionGroupId());
        return out;
    }

    @PostMapping("/modo")
    public ResponseEntity<?> cambiarModo(@RequestBody ModoRequest req) {
        String modoNuevo = req == null ? null : req.modo();
        if (modoNuevo == null
                || (!TelegramModoService.MODO_REAL.equalsIgnoreCase(modoNuevo)
                    && !TelegramModoService.MODO_TEST.equalsIgnoreCase(modoNuevo))) {
            return ResponseEntity.badRequest().body(Map.of("error", "modo debe ser 'real' o 'test'"));
        }
        String efectivo = modo.cambiarModo(modoNuevo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modo", efectivo);
        out.put("test_configurado", props.tieneTest());
        out.put("canal_activo", modo.canalActivo());
        out.put("grupo_activo", modo.grupoActivo());
        out.put("canal_real", props.channelId());
        out.put("grupo_real", props.discussionGroupId());
        return ResponseEntity.ok(out);
    }
}