package api.crow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * Ajustes del procesamiento de imágenes de los posts.
 *
 * Cada imagen (subida o traída de TMDB) se guarda como una COPIA propia en
 * {@code dir}, comprimida y redimensionada. Así el archivo original del equipo
 * del usuario puede borrarse sin afectar al post.
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(
        String dir,
        int maxLado,
        double calidad,
        long maxBytesSubida,
        String tamanoTmdb
) {
    /** Tamaños válidos de imagen de TMDB: w<número>, h<número> o original. */
    private static final Pattern TAMANO_TMDB = Pattern.compile("(?:w\\d+|h\\d+|original)");

    public MediaProperties {
        if (dir == null || dir.isBlank()) dir = "storage/media";
        if (maxLado <= 0) maxLado = 1600;
        if (!Double.isFinite(calidad)) {
            throw new IllegalArgumentException(
                    "app.media.calidad debe ser un número entre 0 y 1 (recibido: " + calidad + ").");
        }
        if (calidad <= 0 || calidad > 1) calidad = 0.85;
        if (maxBytesSubida <= 0) maxBytesSubida = 25L * 1024 * 1024;
        if (tamanoTmdb == null || tamanoTmdb.isBlank()) tamanoTmdb = "w1280";
        if (!TAMANO_TMDB.matcher(tamanoTmdb).matches()) {
            throw new IllegalArgumentException(
                    "app.media.tamano-tmdb no es un tamaño válido de TMDB (ej. w1280): " + tamanoTmdb);
        }
    }
}
