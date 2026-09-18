package api.crow.web;

import api.crow.domain.Comentario;
import api.crow.repository.ComentarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/publicaciones/{publicacionId}/comentarios")
@RequiredArgsConstructor
public class ComentarioController {

    private final ComentarioRepository comentarioRepository;

    @GetMapping
    public ResponseEntity<?> listar(@PathVariable Long publicacionId) {
        List<Comentario> comentarios = comentarioRepository.findByPublicacionIdOrderByFechaAsc(publicacionId);
        List<Map<String, Object>> items = comentarios.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("autor", c.getAutor());
            m.put("texto", c.getTexto());
            m.put("fecha", c.getFecha() == null ? null : c.getFecha().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/count")
    public ResponseEntity<?> contar(@PathVariable Long publicacionId) {
        long count = comentarioRepository.countByPublicacionId(publicacionId);
        return ResponseEntity.ok(Map.of("count", count));
    }
}
