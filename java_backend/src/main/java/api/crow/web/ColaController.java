package api.crow.web;

import api.crow.service.ColaPublicacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cola")
@RequiredArgsConstructor
public class ColaController {

    private final ColaPublicacionService cola;

    @GetMapping
    public List<Map<String, Object>> listar() {
        return cola.listar();
    }

    /** Item activo (pendiente/en curso) de una publicación, para el estado del editor. */
    @GetMapping("/publicacion/{publicacionId}")
    public ResponseEntity<?> activoDePublicacion(@PathVariable Long publicacionId) {
        return cola.activoDePublicacion(publicacionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public ResponseEntity<?> programar(@RequestBody Map<String, Object> body) {
        Long publicacionId = body.get("publicacionId") == null ? null : Long.valueOf(body.get("publicacionId").toString());
        LocalDateTime programadoPara = body.get("programadoPara") == null
                ? null
                : LocalDateTime.parse(body.get("programadoPara").toString());
        Long acoplamientoId = body.get("acoplamientoId") == null ? null : Long.valueOf(body.get("acoplamientoId").toString());
        try {
            return ResponseEntity.ok(cola.programar(publicacionId, programadoPara, acoplamientoId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/fecha")
    public ResponseEntity<?> reprogramar(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        LocalDateTime programadoPara = body.get("programadoPara") == null
                ? null
                : LocalDateTime.parse(body.get("programadoPara").toString());
        try {
            return ResponseEntity.ok(cola.reprogramar(id, programadoPara));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelar(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(cola.cancelar(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reintentar")
    public ResponseEntity<?> reintentar(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(cola.reintentar(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}