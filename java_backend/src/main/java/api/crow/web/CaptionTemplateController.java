package api.crow.web;

import api.crow.domain.CaptionTemplate;
import api.crow.repository.CaptionTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/caption-templates")
@RequiredArgsConstructor
public class CaptionTemplateController {

    private final CaptionTemplateRepository repo;

    @GetMapping
    public List<Map<String, Object>> listar() {
        return repo.findAllByOrderByIsDefaultDescNameAsc()
                .stream()
                .map(this::toMap)
                .toList();
    }

    @GetMapping("/default")
    public ResponseEntity<?> obtenerDefault() {
        return repo.findByIsDefaultTrue()
                .map(t -> ResponseEntity.ok(toMap(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtener(@PathVariable Long id) {
        return repo.findById(id)
                .map(t -> ResponseEntity.ok(toMap(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String template = (String) body.get("template");
        Boolean isDefault = (Boolean) body.getOrDefault("isDefault", false);

        if (name == null || name.isBlank() || template == null || template.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name y template son obligatorios"));
        }
        if (repo.existsByName(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ya existe una plantilla con ese nombre"));
        }
        if (isDefault) {
            repo.findByIsDefaultTrue().ifPresent(t -> {
                t.setIsDefault(false);
                repo.save(t);
            });
        }
        CaptionTemplate t = new CaptionTemplate();
        t.setName(name.trim());
        t.setTemplate(template.trim());
        t.setIsDefault(isDefault);
        repo.save(t);
        return ResponseEntity.ok(toMap(t));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizar(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return repo.findById(id)
                .map(t -> {
                    String name = (String) body.get("name");
                    String template = (String) body.get("template");
                    Boolean isDefault = (Boolean) body.get("isDefault");

                    if (name != null && !name.isBlank() && !name.equals(t.getName())) {
                        if (repo.existsByName(name)) {
                            return ResponseEntity.badRequest().body(Map.of("error", "ya existe una plantilla con ese nombre"));
                        }
                        t.setName(name.trim());
                    }
                    if (template != null && !template.isBlank()) {
                        t.setTemplate(template.trim());
                    }
                    if (isDefault != null && isDefault && !t.getIsDefault()) {
                        repo.findByIsDefaultTrue().ifPresent(dt -> {
                            dt.setIsDefault(false);
                            repo.save(dt);
                        });
                        t.setIsDefault(true);
                    }
                    repo.save(t);
                    return ResponseEntity.ok(toMap(t));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> borrar(@PathVariable Long id) {
        return repo.findById(id)
                .map(t -> {
                    if (t.getIsDefault()) {
                        return ResponseEntity.badRequest().body(Map.of("error", "no se puede borrar la plantilla por defecto"));
                    }
                    repo.delete(t);
                    return ResponseEntity.noContent().<Map<String, Object>>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMap(CaptionTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("template", t.getTemplate());
        m.put("isDefault", t.getIsDefault());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }
}