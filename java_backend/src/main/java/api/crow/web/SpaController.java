package api.crow.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Fallback SPA (aplicación empaquetada).
 * El jar sirve el build de Angular como estático. Esta ruta genérica captura
 * las rutas de navegación del panel (deep links, p.ej. /publicacion/5) y las
 * redirige a index.html para que el router del cliente resuelva. Las rutas
 * reales /api/** tienen sus propios controllers (más específicos) y los
 * assets con extensión (/main.js, /styles.css) no matchean el patrón.
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/", "/**/{p:[^\\.]*}"})
    public String spa() {
        return "forward:/index.html";
    }
}