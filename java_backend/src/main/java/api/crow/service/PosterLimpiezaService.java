package api.crow.service;

import api.crow.config.TmdbProperties;
import api.crow.repository.TituloRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PosterLimpiezaService implements ApplicationRunner {

    private final TmdbProperties tmdb;
    private final TituloRepository tituloRepository;

    private static final String CRON_DIARIO = "0 35 4 * * *";

    @Override
    public void run(ApplicationArguments args) {
        limpiar();
    }

    /**
     * Borra de storage/posters los archivos cuyo mtime supere posterTtlDias (la
     * última descarga reinicia el contador) y deja poster_local_path en null en la
     * BD para los títulos afectados. Si se vuelve a publicar, posterLocal() no
     * encontrará el archivo y lo re-descargará de TMDB, recreándolo desde cero.
     */
    @Scheduled(cron = CRON_DIARIO)
    public void limpiar() {
        Path dir = Path.of(tmdb.posterLocalDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            log.debug("Limpieza de pósters: no existe la carpeta {}", dir);
            return;
        }

        Duration ttl = Duration.ofDays(tmdb.posterTtlDias());
        Instant limite = Instant.now().minus(ttl);
        List<Integer> borrados = new ArrayList<>();

        try (var archivos = Files.list(dir)) {
            archivos.filter(Files::isRegularFile).forEach(file -> {
                try {
                    Instant mtime = Files.getLastModifiedTime(file).toInstant();
                    if (mtime.isBefore(limite) && Files.deleteIfExists(file)) {
                        Integer id = tmdbIdDe(file.getFileName().toString());
                        if (id != null) borrados.add(id);
                    }
                } catch (Exception e) {
                    log.warn("No se pudo limpiar el póster {}: {}", file, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("No se pudo listar {}: {}", dir, e.getMessage());
            return;
        }

        if (!borrados.isEmpty()) {
            sincronizarBd(borrados);
            log.info("Limpieza de pósters: {} archivo(s) con más de {} día(s) sin descargarse, eliminados de {}.",
                    borrados.size(), tmdb.posterTtlDias(), dir);
        } else {
            log.debug("Limpieza de pósters: nada que borrar ({} archivo(s) vigente(s)).", contar(dir));
        }
    }

    private void sincronizarBd(List<Integer> tmdbIds) {
        for (Integer id : tmdbIds) {
            try {
                tituloRepository.findByTmdbId(id)
                        .filter(t -> t.getPosterLocalPath() != null && !t.getPosterLocalPath().isBlank())
                        .ifPresent(t -> {
                            t.setPosterLocalPath(null);
                            tituloRepository.save(t);
                        });
            } catch (Exception e) {
                log.warn("No se pudo sincronizar poster_local_path del tmdbId={}: {}", id, e.getMessage());
            }
        }
    }

    private Integer tmdbIdDe(String nombre) {
        if (nombre == null || !nombre.toLowerCase().endsWith(".jpg")) return null;
        try {
            return Integer.valueOf(nombre.substring(0, nombre.length() - 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long contar(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }
}