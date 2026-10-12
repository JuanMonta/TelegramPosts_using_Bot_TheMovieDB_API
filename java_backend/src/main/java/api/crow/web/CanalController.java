package api.crow.web;

import api.crow.service.CanalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/canales")
@RequiredArgsConstructor
public class CanalController {

    private final CanalService service;

    @GetMapping
    public List<Map<String, Object>> listar() {
        return service.listar();
    }

    @GetMapping("/detectar")
    public ResponseEntity<?> detectar(@RequestParam("chat_id") String chatId,
                                      @RequestParam(value = "bot_id", required = false) Long botId) {
        try {
            return ResponseEntity.ok(service.detectar(chatId, botId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Map<String, Object> body) {
        try {
            String nombre = body.get("nombre") == null ? null : body.get("nombre").toString();
            String chatId = body.get("chat_id") == null ? null : body.get("chat_id").toString();
            String tipo = body.get("tipo") == null ? null : body.get("tipo").toString();
            Long botId = body.get("bot_id") == null || body.get("bot_id").toString().isBlank() ? null : Long.valueOf(body.get("bot_id").toString());
            String chatTipo = body.get("chat_tipo") == null ? null : body.get("chat_tipo").toString();
            return ResponseEntity.ok(service.crear(nombre, chatId, tipo, botId, chatTipo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizar(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String nombre = body.get("nombre") == null ? null : body.get("nombre").toString();
            String chatId = body.get("chat_id") == null ? null : body.get("chat_id").toString();
            String tipo = body.get("tipo") == null ? null : body.get("tipo").toString();
            Boolean activo = body.get("activo") == null ? null : Boolean.parseBoolean(body.get("activo").toString());
            Long botId = body.get("bot_id") == null || body.get("bot_id").toString().isBlank() ? null : Long.valueOf(body.get("bot_id").toString());
            String chatTipo = body.get("chat_tipo") == null ? null : body.get("chat_tipo").toString();
            return ResponseEntity.ok(service.actualizar(id, nombre, chatId, tipo, activo, botId, chatTipo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        try {
            service.eliminar(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
