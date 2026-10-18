package api.crow.config;

import api.crow.service.SugerenciaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CorpusArranque implements ApplicationRunner {

    private final SugerenciaService sugerencias;

    @Override
    public void run(ApplicationArguments args) {
        try {
            sugerencias.registrarGuardados();
        } catch (Exception e) {
            log.warn("Corpus: no se pudo registrar al arrancar "
                    + "(¿base de datos sin crear? Se puede crear desde Configuración → Datos). Detalle: {}",
                    e.getMessage());
        }
    }
}
