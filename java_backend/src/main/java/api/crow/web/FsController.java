package api.crow.web;

import api.crow.service.ExploradorArchivos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Explorador de carpetas del sistema para elegir rutas desde la interfaz
 * (p. ej. las carpetas de respaldos). Solo lista directorios.
 */
@RestController
@RequestMapping("/api/fs")
@RequiredArgsConstructor
public class FsController {

    private final ExploradorArchivos fs;

    @GetMapping("/listar")
    public ResponseEntity<?> listar(@RequestParam(value = "ruta", required = false) String ruta) {
        try {
            return ResponseEntity.ok(fs.listar(ruta));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
