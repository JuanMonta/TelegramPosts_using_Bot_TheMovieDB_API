package api.crow.web;

import api.crow.service.AcoplamientoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/acoplamientos")
@RequiredArgsConstructor
public class AcoplamientoController {

    private final AcoplamientoService service;

    @GetMapping
    public List<Map<String, Object>> listar() {
        return service.listar();
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Map<String, Object> body) {
        try {
            String nombre = body.get("nombre") == null ? null : body.get("nombre").toString();
            return ResponseEntity.ok(service.crear(nombre));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizar(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            String nombre = body.get("nombre") == null ? null : body.get("nombre").toString();
            return ResponseEntity.ok(service.actualizar(id, nombre));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/canales/{canalId}")
    public ResponseEntity<?> agregarCanal(@PathVariable Long id, @PathVariable Long canalId) {
        try {
            return ResponseEntity.ok(service.agregarCanal(id, canalId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/canales/{canalId}")
    public ResponseEntity<?> quitarCanal(@PathVariable Long id, @PathVariable Long canalId) {
        try {
            return ResponseEntity.ok(service.quitarCanal(id, canalId));
        } catch (IllegalArgumentException e) {
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
