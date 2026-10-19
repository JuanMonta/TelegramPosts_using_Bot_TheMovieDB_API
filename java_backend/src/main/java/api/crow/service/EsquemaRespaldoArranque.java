package api.crow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Garantiza el esquema del módulo de respaldos y revisa la integridad al arrancar.
 *
 * 1) Crea (si faltan) las tablas respaldos y config_respaldo con el MISMO DDL que
 *    database/schema.sql (idempotente), para que el módulo funcione aunque no se
 *    haya aplicado la migración a mano.
 * 2) Corre {@link RespaldoService#revisarIntegridad()}: marca 'interrumpido' lo que
 *    quedó 'en_progreso' por un apagón y descarta los *.part huérfanos.
 *
 * Se ejecuta ANTES de la semilla de config (ver @Order en ConfigRespaldoService),
 * porque esa semilla necesita la tabla ya creada.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class EsquemaRespaldoArranque implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final RespaldoService respaldos;
    private final BaseDatosService db;

    private static final String DDL_RESPALDOS = """
            CREATE TABLE IF NOT EXISTS respaldos (
              id              BIGINT AUTO_INCREMENT PRIMARY KEY,
              tipo            ENUM('automatico','manual') NOT NULL,
              estado          ENUM('en_progreso','completado','fallo','interrumpido') NOT NULL DEFAULT 'en_progreso',
              motivo          VARCHAR(40)  NOT NULL DEFAULT 'manual',
              archivo         VARCHAR(255) NOT NULL,
              ruta            VARCHAR(1000) NOT NULL,
              tamano_bytes    BIGINT,
              checksum_sha256 CHAR(64),
              motor           VARCHAR(40)  NOT NULL DEFAULT 'mariadb',
              version         VARCHAR(40),
              mensaje         TEXT,
              iniciado_en     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              terminado_en    DATETIME,
              duracion_ms     BIGINT,
              verificado_en   DATETIME,
              token_ejecucion CHAR(36) NOT NULL,
              INDEX idx_resp_estado   (estado),
              INDEX idx_resp_tipo     (tipo),
              INDEX idx_resp_iniciado (iniciado_en)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String DDL_CONFIG = """
            CREATE TABLE IF NOT EXISTS config_respaldo (
              id                      TINYINT UNSIGNED NOT NULL PRIMARY KEY,
              auto_activo_cifrado     VARBINARY(128),
              modo_cifrado            VARBINARY(128),
              intervalo_horas_cifrado VARBINARY(128),
              hora_diaria_cifrado     VARBINARY(128),
              dir_auto_cifrado        VARBINARY(4096),
              dir_manual_cifrado      VARBINARY(4096),
              bin_dir_cifrado         VARBINARY(4096),
              retencion_cifrado       VARBINARY(128),
              creado_en               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              actualizado_en          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    @Override
    public void run(ApplicationArguments args) {
        if (db.esArchivo()) {
            // Motores de archivo (SQLite): las tablas ya vienen de schema-sqlite.sql
            // (creadas por BootstrapBaseDatos) y el DDL de abajo es sintaxis MariaDB.
            log.debug("Motor de archivo: se omite el DDL del módulo de respaldos (lo aporta schema-sqlite.sql).");
        } else {
            try {
                jdbc.execute(DDL_RESPALDOS);
                jdbc.execute(DDL_CONFIG);
            } catch (Exception e) {
                log.error("No se pudieron preparar las tablas del módulo de respaldos. "
                        + "Aplica database/schema.sql o database/migracion_respaldos.sql a mano. Detalle: {}",
                        e.getMessage());
                return;
            }
        }
        try {
            Map<String, Object> r = respaldos.revisarIntegridad();
            Object interrumpidos = r.get("interrumpidos");
            if (interrumpidos instanceof Integer i && i > 0) {
                log.warn("Respaldos: se detectaron {} operación(es) interrumpida(s) por un cierre abrupto "
                        + "(revisa la pestaña Datos).", i);
            }
        } catch (Exception e) {
            log.warn("No se pudo ejecutar la revisión de integridad de respaldos: {}", e.getMessage());
        }
    }
}
