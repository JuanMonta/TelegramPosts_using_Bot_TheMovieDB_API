package api.crow.config;

import api.crow.service.SugerenciaService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CorpusArranque implements ApplicationRunner {

    private final SugerenciaService sugerencias;

    @Override
    public void run(ApplicationArguments args) {
        sugerencias.registrarGuardados();
    }
}