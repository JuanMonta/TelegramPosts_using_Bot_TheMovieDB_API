package api.crow.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Explorador de carpetas del sistema para que la interfaz pueda elegir rutas
 * (por ejemplo, la carpeta donde se guardan los respaldos). Lista únicamente
 * CARPETAS, nunca el contenido de los archivos.
 *
 * Es para uso local (la app no tiene inicio de sesión y responde solo a
 * localhost), así que no expone nada más que el árbol de directorios.
 */
@Slf4j
@Service
public class ExploradorArchivos {

    public record Item(String nombre, String ruta) {}

    /** Lo que ve el explorador: la carpeta actual, su madre, y atajos y unidades. */
    public record Listado(String ruta, String padre, String separador,
                          List<Item> carpetas, List<Item> sitios, List<Item> unidades) {}

    private static final int MAX_ENTRADAS = 3000;

    public Listado listar(String ruta) {
        Path base = resolverBase(ruta);
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("La carpeta no existe o no se puede abrir: " + base);
        }
        List<Item> carpetas = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(base)) {
            for (Path p : stream) {
                if (carpetas.size() >= MAX_ENTRADAS) break;
                try {
                    if (Files.isDirectory(p)) {
                        carpetas.add(new Item(nombre(p), p.toAbsolutePath().normalize().toString()));
                    }
                } catch (Exception ignore) {
                    // carpeta sin permisos o enlace roto: se omite
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo abrir la carpeta: " + e.getMessage());
        }
        carpetas.sort(Comparator.comparing(Item::nombre, String.CASE_INSENSITIVE_ORDER));

        Path padre = base.getParent();
        return new Listado(base.toString(),
                padre == null ? null : padre.toString(),
                File.separator, carpetas, sitios(), unidades());
    }

    private Path resolverBase(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        }
        Path p = Path.of(ruta.trim()).toAbsolutePath().normalize();
        if (Files.isRegularFile(p) && p.getParent() != null) p = p.getParent();
        return p;
    }

    private String nombre(Path p) {
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }

    private List<Item> sitios() {
        List<Item> out = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        agregar(out, "Inicio", home);
        agregar(out, "Escritorio", home + File.separator + "Desktop");
        agregar(out, "Escritorio", home + File.separator + "Escritorio");
        agregar(out, "Documentos", home + File.separator + "Documents");
        agregar(out, "Descargas", home + File.separator + "Downloads");
        return out;
    }

    private void agregar(List<Item> out, String nombre, String ruta) {
        if (ruta == null || ruta.isBlank()) return;
        try {
            Path p = Path.of(ruta);
            if (Files.isDirectory(p)) {
                String abs = p.toAbsolutePath().normalize().toString();
                if (out.stream().noneMatch(i -> i.ruta().equals(abs))) {
                    out.add(new Item(nombre, abs));
                }
            }
        } catch (Exception ignore) {
            // ruta inválida: se omite
        }
    }

    private List<Item> unidades() {
        List<Item> out = new ArrayList<>();
        for (File r : File.listRoots()) {
            out.add(new Item(r.getPath(), r.getPath()));
        }
        return out;
    }
}
