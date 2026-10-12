package api.crow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado de la configuración en reposo con AES-256-GCM (confidencialidad +
 * autenticación en un solo paso, estándar de la industria para campos).
 *
 * Formato por valor: [1 byte de versión][12 bytes de IV aleatorio][ciphertext+tag]
 * con tag de 128 bits. IV aleatorio por valor: el mismo texto nunca produce el
 * mismo ciphertext.
 *
 * Clave maestra (32 bytes): se toma de la variable de entorno CROW_CLAVE_MAESTRA
 * (Base64) si existe — recomendado en producción — o, si no, del archivo
 * clave_maestra.bin. El archivo se busca primero en la carpeta de arranque y
 * despues junto al codigo que corre (padres del jar o de target/classes), para
 * que la clave NO dependa de desde donde se lanzo el proceso: ni la IP, ni el
 * puerto, ni la carpeta de arranque la alteran. Si no se encuentra en ningun
 * lado se genera una vez en la carpeta de arranque. Si se pierde la clave, los
 * valores cifrados no se pueden descifrar: hay que re-ingresarlos desde la UI.
 */
@Slf4j
@Service
public class ConfigCifradoService {

    private static final String ALGORITMO = "AES/GCM/NoPadding";
    private static final String ENV_CLAVE = "CROW_CLAVE_MAESTRA";
    private static final String ARCHIVO_CLAVE = "clave_maestra.bin";
    private static final int TAM_CLAVE = 32;   // AES-256
    private static final int TAM_IV = 12;       // recomendado para GCM
    private static final int TAG_BITS = 128;
    private static final byte VERSION = 0x01;

    private final SecretKeySpec clave;
    private final SecureRandom random = new SecureRandom();

    public ConfigCifradoService() {
        this.clave = cargarClave();
    }

    /** Cifra un texto. Devuelve null para entrada null. */
    public byte[] cifrar(String texto) {
        if (texto == null) return null;
        try {
            byte[] iv = new byte[TAM_IV];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + TAM_IV + ct.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, TAM_IV);
            System.arraycopy(ct, 0, out, 1 + TAM_IV, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar la configuración", e);
        }
    }

    /** Descifra un valor producido por {@link #cifrar}. null/vacío -> null. */
    public String descifrar(byte[] dato) {
        if (dato == null || dato.length == 0) return null;
        if (dato.length < 1 + TAM_IV + TAG_BITS / 8) {
            throw new IllegalStateException("Dato cifrado corrupto (muy corto)");
        }
        if (dato[0] != VERSION) {
            throw new IllegalStateException("Dato cifrado con una versión desconocida (0x"
                    + Integer.toHexString(dato[0] & 0xff) + ")");
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(TAG_BITS, dato, 1, TAM_IV));
            byte[] pt = cipher.doFinal(dato, 1 + TAM_IV, dato.length - 1 - TAM_IV);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "No se pudo descifrar la configuración: la clave maestra no coincide "
                    + "con la usada al guardar (¿se cambió " + ENV_CLAVE + " o se borró "
                    + ARCHIVO_CLAVE + "?). Re-ingresá los valores desde la UI.", e);
        }
    }

    private SecretKeySpec cargarClave() {
        String b64 = System.getenv(ENV_CLAVE);
        if (b64 != null && !b64.isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "La variable de entorno " + ENV_CLAVE + " no es Base64 válido", e);
            }
            if (bytes.length != TAM_CLAVE) {
                throw new IllegalStateException("La variable de entorno " + ENV_CLAVE
                        + " debe ser Base64 de 32 bytes (AES-256); tiene " + bytes.length);
            }
            log.info("Cifrado: clave maestra cargada desde la variable de entorno {}", ENV_CLAVE);
            return new SecretKeySpec(bytes, "AES");
        }
        Path archivo = resolverArchivoClave();
        try {
            if (Files.exists(archivo)) {
                byte[] bytes = Files.readAllBytes(archivo);
                if (bytes.length != TAM_CLAVE) {
                    throw new IllegalStateException("El archivo " + archivo.toAbsolutePath()
                            + " debe tener 32 bytes; tiene " + bytes.length);
                }
                log.info("Cifrado: clave maestra cargada desde {}", archivo.toAbsolutePath());
                return new SecretKeySpec(bytes, "AES");
            }
            byte[] nueva = new byte[TAM_CLAVE];
            random.nextBytes(nueva);
            Files.write(archivo, nueva);
            log.warn("Cifrado: se generó una clave maestra nueva en {}. Exportala como {} "
                    + "(Base64 de 32 bytes) y protegé el archivo: si se pierde, la "
                    + "configuración cifrada no se puede descifrar.",
                    archivo.toAbsolutePath(), ENV_CLAVE);
            return new SecretKeySpec(nueva, "AES");
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer/escribir " + archivo.toAbsolutePath(), e);
        }
    }

    /**
     * Ubicacion del archivo de clave sin depender de la carpeta de arranque:
     * 1) ./clave_maestra.bin (comportamiento historico),
     * 2) subiendo hasta 3 niveles desde donde corre el codigo (cubre
     *    java_backend/target/*.jar y target/classes del IDE).
     * Si no existe en ningun lado, se usara (y creara) el de la carpeta de arranque.
     */
    private static Path resolverArchivoClave() {
        Path local = Path.of(ARCHIVO_CLAVE);
        try {
            if (Files.exists(local)) return local;
            Path base = codigoBase();
            Path dir = base == null ? null : (Files.isDirectory(base) ? base : base.getParent());
            for (int i = 0; dir != null && i < 4; i++) {
                Path candidato = dir.resolve(ARCHIVO_CLAVE);
                if (Files.exists(candidato)) {
                    log.info("Cifrado: clave encontrada junto al codigo en {}", candidato.toAbsolutePath());
                    return candidato;
                }
                dir = dir.getParent();
            }
        } catch (Exception e) {
            log.warn("Cifrado: no se pudo resolver la ubicacion junto al codigo, se usa la carpeta de arranque");
        }
        return local;
    }

    private static Path codigoBase() {
        try {
            java.net.URI uri = ConfigCifradoService.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return Path.of(uri);
        } catch (Exception e) {
            return null;
        }
    }
}
