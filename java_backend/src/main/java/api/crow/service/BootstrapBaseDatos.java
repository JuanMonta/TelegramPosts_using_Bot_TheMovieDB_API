package api.crow.service;

import api.crow.config.ConfigBootstrapDb;
import api.crow.config.ConfigBootstrapDb.Bootstrap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Primer arranque automático (headless). Si config-db.yaml trae credenciales y la
 * base indicada NO existe, la crea con {@link BaseDatosAdminService#crear} (escenario A).
 * Si la base ya existe pero está vacía (sin tablas), aplica el DDL (escenario B).
 *
 * Es idempotente y nunca destructivo: si la base ya existe y tiene tablas, no hace
 * nada. Se ejecuta antes que el resto de arranques (ver @Order) para que, al
 * terminar, los demás runners encuentren la base.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class BootstrapBaseDatos implements ApplicationRunner {

    private final ConfigBootstrapDb cfg;
    private final BaseDatosAdminService admin;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Bootstrap b = cfg.cargar();
            if (!b.enabled()) return;
            if (esBlanco(b.usuarioAdmin())) return;      // sin credenciales no se auto-crea
            if (esBlanco(b.nombreBase())) return;

            if (!admin.existeBase(b)) {
                log.info("Bootstrap: la base '{}' no existe; se intentará crear (primera ejecución).",
                        b.nombreBase());
                admin.crear(b);
                log.info("Bootstrap: base '{}' creada.", b.nombreBase());
                return;
            }

            // La base existe: si está vacía (escenario B), se crean las tablas.
            long tablas = admin.contarTablas(b);
            if (tablas == 0) {
                log.info("Bootstrap: la base '{}' existe pero está vacía; se crearán las tablas.", b.nombreBase());
                admin.crearTablas(b);
                log.info("Bootstrap: tablas de '{}' creadas.", b.nombreBase());
            }
        } catch (Exception e) {
            log.warn("Bootstrap de base de datos: no se pudo preparar automáticamente. "
                    + "Puedes hacerlo desde Configuración → Datos. Detalle: {}", e.getMessage());
        }
    }

    private static boolean esBlanco(String s) {
        return s == null || s.isBlank();
    }
}
