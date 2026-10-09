package api.crow.service;

import api.crow.config.TelegramProperties;
import api.crow.domain.Setting;
import api.crow.repository.SettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Modo de publicación "real" vs "prueba".
 * El estado se persiste en la tabla `settings` (clave "telegram_modo") para que
 * sobreviva a reinicios, y se cambia en runtime desde el frontend. En modo
 * prueba se publica en el canal/grupo de pruebas configurado (mismo bot).
 */
@Service
@RequiredArgsConstructor
public class TelegramModoService {

    public static final String MODO_REAL = "real";
    public static final String MODO_TEST = "test";
    private static final String CLAVE = "telegram_modo";

    private final SettingRepository settings;
    private final TelegramProperties props;

    public String modoActual() {
        Optional<Setting> s = settings.findById(CLAVE);
        String valor = s.map(Setting::getValor).orElse(null);
        return MODO_TEST.equals(valor) ? MODO_TEST : MODO_REAL;
    }

    public boolean modoTest() {
        return MODO_TEST.equals(modoActual());
    }

    /** Canal activo según el modo (real por defecto). */
    public String canalActivo() {
        if (modoTest() && props.tieneTest()) return props.testChannelId();
        return props.channelId();
    }

    /** Grupo de discusión activo según el modo. */
    public String grupoActivo() {
        if (modoTest() && props.tieneTest()) return props.testDiscussionGroupId();
        return props.discussionGroupId();
    }

    /** Cambia el modo en runtime (persistido). Devuelve el modo efectivo. */
    public String cambiarModo(String modo) {
        String efectivo = MODO_TEST.equalsIgnoreCase(modo) ? MODO_TEST : MODO_REAL;
        Setting s = settings.findById(CLAVE).orElseGet(Setting::new);
        s.setClave(CLAVE);
        s.setValor(efectivo);
        s.setActualizadoEn(LocalDateTime.now());
        settings.save(s);
        return efectivo;
    }
}