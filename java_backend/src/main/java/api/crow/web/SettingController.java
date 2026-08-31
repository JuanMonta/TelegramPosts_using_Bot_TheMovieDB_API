package api.crow.web;

import api.crow.domain.Setting;
import api.crow.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingController {

    public static final String CLAVE_PLANTILLA_CAPTION = "caption_template";

    private final SettingRepository settingRepository;

    /**
     * Plantilla por defecto usada para generar captions nuevos.
     * Placeholders: {title} {year} {rating} {votes} {genres} {synopsis} {providers}
     */
    public static final String PLANTILLA_DEFECTO =
            "{title} ({year})\n" +
            "{rating} · {votes} votos\n" +
            "{genres}\n" +
            "\n" +
            "{synopsis}\n" +
            "\n" +
            "Disponible en: {providers}\n" +
            "\n" +
            "Español Latino.\n" +
            "\n" +
            "Contraseña: TeraMoviesMKVFullHD";

    @GetMapping("/caption-template")
    public ResponseEntity<?> obtenerPlantilla() {
        String valor = settingRepository.findById(CLAVE_PLANTILLA_CAPTION)
                .map(Setting::getValor)
                .orElse(PLANTILLA_DEFECTO);
        return ResponseEntity.ok(Map.of("plantilla", valor, "placeholders", new String[]{"title", "year", "rating", "votes", "genres", "synopsis", "providers"}));
    }

    @PutMapping("/caption-template")
    public ResponseEntity<?> guardarPlantilla(@RequestBody Map<String, String> body) {
        String plantilla = body.get("plantilla");
        if (plantilla == null || plantilla.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "la plantilla no puede estar vacía"));
        }
        Setting s = settingRepository.findById(CLAVE_PLANTILLA_CAPTION).orElseGet(Setting::new);
        s.setClave(CLAVE_PLANTILLA_CAPTION);
        s.setValor(plantilla);
        s.setActualizadoEn(LocalDateTime.now());
        settingRepository.save(s);
        return ResponseEntity.ok(Map.of("plantilla", s.getValor()));
    }
}
