package api.crow.web;

import api.crow.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Sirve las imágenes propias de los posts (copias guardadas en storage/media).
 * Los nombres son aleatorios e inmutables, así que se pueden cachear fuerte.
 */
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService media;

    @GetMapping("/{archivo}")
    public ResponseEntity<byte[]> ver(@PathVariable("archivo") String archivo) {
        Path p = media.resolver(archivo);
        if (p == null || !Files.isRegularFile(p)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(p);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
