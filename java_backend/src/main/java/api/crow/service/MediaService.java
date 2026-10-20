package api.crow.service;

import api.crow.config.MediaProperties;
import api.crow.config.TmdbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Procesa y guarda las imágenes de los posts.
 *
 * Estrategia (heredada del compresor de referencia del usuario): leer con
 * ImageIO, redimensionar proporcionalmente a un lado máximo, aplanar la
 * transparencia sobre blanco y re-codificar como JPEG con calidad explícita.
 * Esto logra mucha reducción de tamaño sin pérdida perceptible. Se corrige
 * además la orientación EXIF de las fotos.
 *
 * Se guarda una COPIA propia en {@code app.media.dir}; el archivo original del
 * usuario puede borrarse sin afectar al post.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    /** Resultado del procesado de una imagen. */
    public record Resultado(String archivo, int ancho, int alto, long bytes) {}

    private static final Set<String> EXTENSIONES_PERMITIDAS = Set.of("jpg", "jpeg");

    private final MediaProperties props;
    private final TmdbProperties tmdb;

    private final RestClient descarga = RestClient.builder()
            .baseUrl("https://image.tmdb.org/t/p")
            .build();

    /** Procesa una imagen subida por el usuario y guarda la copia. */
    public Resultado guardarSubida(byte[] original) {
        if (original == null || original.length == 0) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }
        if (original.length > props.maxBytesSubida()) {
            throw new IllegalArgumentException("La imagen supera el máximo permitido ("
                    + (props.maxBytesSubida() / (1024 * 1024)) + " MB).");
        }
        byte[] jpeg = comprimir(original);
        BufferedImage img = leer(jpeg);
        String archivo = nombreNuevo();
        escribir(archivo, jpeg);
        return new Resultado(archivo, img.getWidth(), img.getHeight(), jpeg.length);
    }

    /** Descarga una imagen de TMDB, la procesa y guarda la copia. */
    public Resultado guardarDesdeTmdb(String tmdbPath) {
        if (tmdbPath == null || tmdbPath.isBlank()) {
            throw new IllegalArgumentException("La imagen de TMDB no es válida.");
        }
        byte[] original;
        try {
            original = descarga.get()
                    .uri("/" + props.tamanoTmdb() + tmdbPath)
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descargar la imagen desde TMDB: " + e.getMessage());
        }
        if (original == null || original.length == 0) {
            throw new IllegalStateException("TMDB devolvió una imagen vacía.");
        }
        byte[] jpeg = comprimir(original);
        BufferedImage img = leer(jpeg);
        String archivo = nombreNuevo();
        escribir(archivo, jpeg);
        return new Resultado(archivo, img.getWidth(), img.getHeight(), jpeg.length);
    }

    /** Archivo listo para subir a Telegram. */
    public Resource recurso(String archivo) {
        Path p = resolver(archivo);
        if (p == null || !Files.isRegularFile(p)) {
            throw new IllegalStateException("La imagen adjunta ya no existe en el disco: " + archivo);
        }
        return new FileSystemResource(p);
    }

    /** Ruta absoluta y segura dentro de storage/media (null si no es válida). */
    public Path resolver(String archivo) {
        if (archivo == null || archivo.isBlank()) return null;
        if (archivo.contains("..") || archivo.contains("/") || archivo.contains("\\")) return null;
        int punto = archivo.lastIndexOf('.');
        String base = punto >= 0 ? archivo.substring(0, punto) : archivo;
        String ext = punto >= 0 ? archivo.substring(punto + 1).toLowerCase(Locale.ROOT) : "";
        if (base.isEmpty() || !base.chars().allMatch(Character::isLetterOrDigit)
                || !EXTENSIONES_PERMITIDAS.contains(ext)) {
            return null;
        }
        try {
            Path dir = dirAbsoluto();
            Path p = dir.resolve(archivo).normalize();
            if (!p.startsWith(dir)) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** Borra el archivo (silencioso si ya no existe). */
    public void borrar(String archivo) {
        Path p = resolver(archivo);
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            log.warn("No se pudo borrar la imagen {}: {}", archivo, e.getMessage());
        }
    }

    // ---- procesado ----

    /** Redimensiona, orienta y re-codifica a JPEG. */
    public byte[] comprimir(byte[] original) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) {
                throw new IllegalArgumentException("El archivo no es una imagen compatible (usa JPG o PNG).");
            }
            BufferedImage img = escalar(src);
            int orientacion = orientacionExif(original);
            if (orientacion > 1) img = orientar(img, orientacion);
            img = aplanar(img);
            return codificarJpeg(img);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo procesar la imagen: " + e.getMessage());
        }
    }

    private BufferedImage escalar(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int mayor = Math.max(w, h);
        if (mayor <= props.maxLado()) return src;
        double f = (double) props.maxLado() / mayor;
        int nw = Math.max(1, (int) Math.round(w * f));
        int nh = Math.max(1, (int) Math.round(h * f));
        return redibujar(src, nw, nh, false);
    }

    /** Aplana transparencia sobre blanco y pasa a RGB (necesario para JPEG). */
    private BufferedImage aplanar(BufferedImage src) {
        return redibujar(src, src.getWidth(), src.getHeight(), true);
    }

    private BufferedImage redibujar(BufferedImage src, int nw, int nh, boolean fondo) {
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (fondo) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
        }
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private BufferedImage orientar(BufferedImage src, int o) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = o >= 5;
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx, sy;
                switch (o) {
                    case 2 -> { sx = w - 1 - x; sy = y; }
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }
                    case 4 -> { sx = x; sy = h - 1 - y; }
                    case 5 -> { sx = y; sy = x; }
                    case 6 -> { sx = y; sy = h - 1 - x; }
                    case 7 -> { sx = h - 1 - y; sy = w - 1 - x; }
                    case 8 -> { sx = w - 1 - y; sy = x; }
                    default -> { sx = x; sy = y; }
                }
                dst.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return dst;
    }

    private byte[] codificarJpeg(BufferedImage img) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) props.calidad());
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private BufferedImage leer(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer la imagen procesada.");
        }
    }

    // ---- EXIF ----

    /** Devuelve la orientación EXIF (1..8); 1 si no aplica. */
    static int orientacionExif(byte[] data) {
        try {
            if (data == null || data.length < 4) return 1;
            if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return 1; // no es JPEG
            int i = 2;
            while (i + 4 <= data.length) {
                if ((data[i] & 0xFF) != 0xFF) return 1;
                int marker = data[i + 1] & 0xFF;
                int size = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
                if (marker == 0xE1) {
                    int p = i + 4;
                    if (p + 6 <= data.length && data[p] == 'E' && data[p + 1] == 'x'
                            && data[p + 2] == 'i' && data[p + 3] == 'f') {
                        return leerTiff(data, p + 6);
                    }
                    return 1;
                }
                if (marker == 0xDA || marker == 0xD9) break; // inicio del escaneo / fin
                if (size < 2) break;
                i += 2 + size;
            }
        } catch (Exception ignore) {
            // ante cualquier duda: sin orientación
        }
        return 1;
    }

    private static int leerTiff(byte[] d, int base) {
        if (base + 8 > d.length) return 1;
        boolean le = d[base] == 'I' && d[base + 1] == 'I';
        boolean be = d[base] == 'M' && d[base + 1] == 'M';
        if (!le && !be) return 1;
        int ifdOff = le ? u16le(d, base + 4) : u16be(d, base + 4);
        int ifd = base + ifdOff;
        if (ifd + 2 > d.length) return 1;
        int count = le ? u16le(d, ifd) : u16be(d, ifd);
        for (int k = 0; k < count; k++) {
            int e = ifd + 2 + k * 12;
            if (e + 12 > d.length) return 1;
            int tag = le ? u16le(d, e) : u16be(d, e);
            if (tag == 0x0112) {
                int v = le ? u16le(d, e + 8) : u16be(d, e + 8);
                return (v >= 1 && v <= 8) ? v : 1;
            }
        }
        return 1;
    }

    private static int u16le(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static int u16be(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    // ---- almacenamiento ----

    private Path dirAbsoluto() {
        return Path.of(props.dir()).toAbsolutePath().normalize();
    }

    private String nombreNuevo() {
        return UUID.randomUUID().toString().replace("-", "") + ".jpg";
    }

    private void escribir(String archivo, byte[] bytes) {
        try {
            Path dir = dirAbsoluto();
            Files.createDirectories(dir);
            Files.write(dir.resolve(archivo), bytes);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo guardar la imagen: " + e.getMessage());
        }
    }
}
