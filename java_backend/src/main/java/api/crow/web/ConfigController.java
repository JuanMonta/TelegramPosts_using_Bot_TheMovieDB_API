package api.crow.web;

import api.crow.config.TelegramProperties;
import api.crow.service.ConfigService;
import api.crow.service.TelegramModoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Estado y edición de la configuración.
 * GET  -> estado completo (el token SIEMPRE llega enmascarado, jamás el valor real)
 * POST -> guarda campos cambiados; null = conservar. Los valores quedan cifrados
 *         en config_app y se aplican en caliente (cola, canales, límites, token).
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final TelegramModoService modo;
    private final TelegramProperties props;
    private final ConfigService config;

    record ModoRequest(String modo) {}

    @GetMapping
    public Map<String, Object> config() {
        ConfigService.Configuracion c = config.cargar();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modo", modo.modoActual());
        out.put("test_configurado", props.tieneTest() || (c.canalPrueba() != null && !c.canalPrueba().isBlank()));
        out.put("canal_activo", modo.canalActivo());
        out.put("grupo_activo", modo.grupoActivo());
        out.put("canal_real", config.canalPrincipal());
        out.put("grupo_real", props.discussionGroupId());
        // Editables (el token solo como máscara + flag)
        out.put("bot_token", ConfigService.enmascarar(config.tokenBot()));
        out.put("bot_token_configurado", config.tokenBot() != null && !config.tokenBot().isBlank());
        out.put("canal_principal", config.canalPrincipal());
        out.put("canal_prueba", config.canalPrueba());
        out.put("limite_caption", c.limiteCaption());
        out.put("intervalo_cola_ms", c.intervaloColaMs());
        out.put("colgada_minutos", c.colgadaMinutos());
        out.put("clave_invalida", config.claveInvalida());
        return out;
    }

    /** Guarda la configuración. Cada campo null conserva el valor actual. */
    @PostMapping
    public ResponseEntity<?> guardar(@RequestBody(required = false) Map<String, Object> body) {
        try {
            ConfigService.Guardar g = new ConfigService.Guardar(
                    texto(body, "bot_token"),
                    texto(body, "canal_principal"),
                    texto(body, "canal_prueba"),
                    entero(body, "limite_caption"),
                    largo(body, "intervalo_cola_ms"),
                    entero(body, "colgada_minutos"));
            config.guardar(g);
            return ResponseEntity.ok(config());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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

    // ---- lectura tipada del payload (null = conservar) ----

    private String texto(Map<String, Object> body, String clave) {
        if (body == null || !body.containsKey(clave)) return null;
        Object v = body.get(clave);
        return v == null ? null : v.toString();
    }

    private Integer entero(Map<String, Object> body, String clave) {
        Object v = body == null ? null : body.get(clave);
        if (v == null) return null;
        try {
            return (int) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(clave + " debe ser un número");
        }
    }

    private Long largo(Map<String, Object> body, String clave) {
        Object v = body == null ? null : body.get(clave);
        if (v == null) return null;
        try {
            return (long) Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(clave + " debe ser un número");
        }
    }
}
