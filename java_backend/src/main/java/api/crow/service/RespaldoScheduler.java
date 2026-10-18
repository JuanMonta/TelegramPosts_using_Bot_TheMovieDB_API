package api.crow.service;

import api.crow.domain.ConfigRespaldo;
import api.crow.domain.Respaldo;
import api.crow.repository.RespaldoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Dispara el respaldo automático cuando toca, según la frecuencia elegida
 * (cada N horas o diario a una hora). Comprueba cada minuto: es barato y así
 * respeta cambios de configuración sin reiniciar.
 *
 * No usa cron porque el usuario quiere elegir entre intervalo o hora fija.
 * Si ya hay una operación en curso, espera a la siguiente pasada (nunca encima).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RespaldoScheduler {

    private final RespaldoService respaldos;
    private final ConfigRespaldoService cfg;
    private final RespaldoRepository repo;

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void revisar() {
        ConfigRespaldoService.Config c = cfg.cargar();
        if (!c.autoActivo()) return;
        if (respaldos.ocupado()) return;
        if (!toca(c)) return;
        try {
            respaldos.iniciarRespaldo(Respaldo.TIPO_AUTOMATICO, Respaldo.MOTIVO_PROGRAMADO);
            log.info("Respaldo automático lanzado (modo {}, retención {})", c.modo(), c.retencion());
        } catch (IllegalStateException e) {
            log.debug("Respaldo automático postergado: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("No se pudo lanzar el respaldo automático: {}", e.getMessage());
        }
    }

    /** ¿Ya pasó el momento del próximo respaldo automático? */
    private boolean toca(ConfigRespaldoService.Config c) {
        Optional<Respaldo> ultimo = repo.findFirstByTipoOrderByIniciadoEnDesc(Respaldo.TIPO_AUTOMATICO);
        LocalDateTime ahora = LocalDateTime.now();

        if (ConfigRespaldo.MODO_DIARIO.equals(c.modo())) {
            LocalDateTime programado = LocalDate.now().atTime(LocalTime.parse(c.horaDiaria()));
            if (ahora.isBefore(programado)) return false;
            return ultimo.isEmpty() || ultimo.get().getIniciadoEn().isBefore(programado);
        }

        if (ultimo.isEmpty()) return true;
        LocalDateTime proximo = ultimo.get().getIniciadoEn().plusHours(c.intervaloHoras());
        return !proximo.isAfter(ahora);
    }
}
