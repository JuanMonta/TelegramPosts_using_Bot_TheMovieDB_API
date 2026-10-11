package api.crow.service;

import api.crow.domain.ColaPublicacion;
import api.crow.domain.LinkVideo;
import api.crow.domain.Publicacion;
import api.crow.repository.ColaPublicacionRepository;
import api.crow.repository.LinkVideoRepository;
import api.crow.repository.PublicacionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cola de publicaciones programadas: guarda borradores con su hora y los manda
 * al canal cuando llegan. Cada item conserva el modo en que se programó
 * (real/prueba) para publicar siempre al destino elegido aunque luego cambie
 * el modo activo. El envío es seguro ante varias instancias del backend: un
 * reclamo atómico (pendiente -> publicando) garantiza que un post se publica
 * exactamente una vez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColaPublicacionService implements ApplicationRunner {

    /** Tick fijo de despertador: cada segundo chequea si ya venció el intervalo
     * configurado (config_app), lo que permite cambiarlo en caliente. */
    private static final long TICK_MS = 1_000;

    private final ColaPublicacionRepository cola;
    private final PublicacionRepository publicaciones;
    private final LinkVideoRepository links;
    private final TelegramPublisher telegram;
    private final TelegramModoService modo;
    private final ConfigService config;
    private final PlatformTransactionManager txManager;

    /** Última vez que corrió el barrido (este proceso). */
    private volatile long ultimoProceso;

    public static String errorCaptionLargo(long n, long limite) {
        return "El caption tiene " + n + " caracteres; Telegram permite máx. " + limite
                + " en fotos. Acortaldo en el editor y presioná Reintentar.";
    }

    /** Intervalo efectivo hoy (configurable desde Configuración). */
    public long intervaloMs() {
        return config.intervaloColaMs();
    }

    @Override
    public void run(ApplicationArguments args) {
        // Sincroniza la cola al arrancar: si la app estuvo apagada, los items
        // atrasados se publican en cuanto vuelve. El reclamo atómico evita que
        // la segunda instancia (9001) los duplique.
        procesar();
    }

    @Scheduled(fixedDelay = TICK_MS)
    public void procesar() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoProceso < config.intervaloColaMs()) {
            return; // aún no vence el intervalo configurado
        }
        ultimoProceso = ahora;
        try {
            TransactionTemplate tx = tx();
            tx.execute(status -> {
                cola.liberarColgadas(LocalDateTime.now().minusMinutes(config.colgadaMinutos()));
                return null;
            });
            List<ColaPublicacion> debidas = cola.findDebidas(ColaPublicacion.ESTADO_PENDIENTE, LocalDateTime.now());
            for (ColaPublicacion item : debidas) {
                publicar(item);
            }
        } catch (Exception e) {
            log.error("Error procesando la cola de publicaciones: {}", e.getMessage(), e);
        }
    }

    // ---- API para el frontend ----

    public List<Map<String, Object>> listar() {
        return cola.findAllConPublicacion().stream().map(this::toMap).toList();
    }

    /** Item activo (pendiente/en curso) de una publicación, si existe. */
    public Optional<Map<String, Object>> activoDePublicacion(Long publicacionId) {
        return cola.findActivoPorPublicacion(publicacionId).map(this::toMap);
    }

    public Map<String, Object> programar(Long publicacionId, LocalDateTime programadoPara) {
        if (programadoPara == null) {
            throw new IllegalArgumentException("programadoPara es obligatorio");
        }
        Publicacion p = publicaciones.findById(publicacionId)
                .orElseThrow(() -> new IllegalArgumentException("La publicación no existe"));
        if (!"borrador".equals(p.getEstado())) {
            throw new IllegalArgumentException("Solo se pueden programar borradores (el post ya está publicado).");
        }
        if (cola.tieneActivo(publicacionId)) {
            throw new IllegalArgumentException("Ese borrador ya está programado en la cola.");
        }
        long limite = config.limiteCaption();
        long longitud = p.getCaption() == null ? 0 : p.getCaption().codePointCount(0, p.getCaption().length());
        if (longitud > limite) {
            throw new IllegalArgumentException("El caption supera el límite de Telegram (" + longitud + " caracteres > "
                    + limite + "). Acórtalo en el editor antes de programarlo.");
        }
        ColaPublicacion item = new ColaPublicacion();
        item.setPublicacion(p);
        item.setProgramadoPara(programadoPara);
        item.setModo(modo.modoActual());
        item.setEstado(ColaPublicacion.ESTADO_PENDIENTE);
        return toMap(cola.save(item));
    }

    public Map<String, Object> cancelar(Long id) {
        ColaPublicacion item = cola.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El item no existe"));
        if (!ColaPublicacion.ESTADO_PENDIENTE.equals(item.getEstado())) {
            throw new IllegalArgumentException("Solo se puede cancelar un item pendiente.");
        }
        item.setEstado(ColaPublicacion.ESTADO_CANCELADO);
        return toMap(cola.save(item));
    }

    public Map<String, Object> reprogramar(Long id, LocalDateTime programadoPara) {
        if (programadoPara == null) {
            throw new IllegalArgumentException("programadoPara es obligatorio");
        }
        ColaPublicacion item = cola.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El item no existe"));
        if (!ColaPublicacion.ESTADO_PENDIENTE.equals(item.getEstado())) {
            throw new IllegalArgumentException("Solo se puede reprogramar un item pendiente.");
        }
        long limite = config.limiteCaption();
        long longitud = item.getPublicacion().getCaption() == null ? 0
                : item.getPublicacion().getCaption().codePointCount(0, item.getPublicacion().getCaption().length());
        if (longitud > limite) {
            throw new IllegalArgumentException("El caption supera el límite de Telegram (" + longitud + " caracteres > "
                    + limite + "). Acórtalo antes de reprogramarlo.");
        }
        item.setProgramadoPara(programadoPara);
        return toMap(cola.save(item));
    }

    /** Reintenta un item fallido (solo 'fallo' o 'cancelado' si el post sigue en borrador). */
    public Map<String, Object> reintentar(Long id) {
        ColaPublicacion item = cola.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("El item no existe"));
        String est = item.getEstado();
        if (!ColaPublicacion.ESTADO_FALLO.equals(est) && !ColaPublicacion.ESTADO_CANCELADO.equals(est)) {
            throw new IllegalArgumentException("Solo se puede reintentar un item que falló o se canceló.");
        }
        Publicacion p = item.getPublicacion();
        if (!"borrador".equals(p.getEstado())) {
            throw new IllegalArgumentException("El post ya no es un borrador; no puede reprogramarse.");
        }
        long limite = config.limiteCaption();
        long longitud = p.getCaption() == null ? 0 : p.getCaption().codePointCount(0, p.getCaption().length());
        if (longitud > limite) {
            throw new IllegalArgumentException("El caption sigue superando el límite de Telegram (" + longitud + " > "
                    + limite + "). Acórtalo en el editor antes de reintentar.");
        }
        item.setEstado(ColaPublicacion.ESTADO_PENDIENTE);
        item.setError(null);
        item.setUltimoIntento(null);
        return toMap(cola.save(item));
    }

    // ---- ejecución ----

    private void publicar(ColaPublicacion item) {
        try {
            boolean reclamado = Boolean.TRUE.equals(tx().execute(status ->
                    cola.reclamar(item.getId(), LocalDateTime.now()) == 1));
            if (!reclamado) {
                return; // otra instancia se lo llevó: no duplicar
            }

            Publicacion p = item.getPublicacion();
            if (!"borrador".equals(p.getEstado())) {
                // El post se publicó a mano mientras esperaba su turno: se cancela el item.
                tx().executeWithoutResult(status -> {
                    ColaPublicacion c = cola.findById(item.getId()).orElse(null);
                    if (c == null) return;
                    c.setEstado(ColaPublicacion.ESTADO_CANCELADO);
                    c.setError("El post ya se publicó mientras estaba en la cola.");
                    cola.save(c);
                });
                return;
            }

            long limite = config.limiteCaption();
            long longitud = p.getCaption() == null ? 0 : p.getCaption().codePointCount(0, p.getCaption().length());
            if (longitud > limite) {
                // Fallo sin gastar la llamada a Telegram: el caption no cabe en una foto.
                tx().executeWithoutResult(status -> {
                    ColaPublicacion c = cola.findById(item.getId()).orElse(null);
                    if (c == null) return;
                    c.setEstado(ColaPublicacion.ESTADO_FALLO);
                    c.setError(errorCaptionLargo(longitud, limite));
                    c.setUltimoIntento(LocalDateTime.now());
                    cola.save(c);
                });
                log.warn("Cola: el item {} no cabe (caption {} caracteres)", item.getId(), longitud);
                return;
            }

            List<LinkVideo> enlaces = links.findByTituloIdOrderByOrdenAsc(p.getTitulo().getId());
            String canal = modo.canalDeModo(item.getModo());
            Map<String, Object> resp = telegram.sendPhoto(p, enlaces, canal);

            tx().executeWithoutResult(status -> {
                ColaPublicacion c = cola.findById(item.getId()).orElse(null);
                if (c == null) return;
                c.setEstado(ColaPublicacion.ESTADO_PUBLICADO);
                c.setPublicadoEn(LocalDateTime.now());
                c.setTelegramMessageId(((Number) resp.get("message_id")).longValue());
                c.setError(null);
                cola.save(c);

                Publicacion pub = c.getPublicacion();
                pub.setEstado("publicado");
                pub.setModo(c.getModo());
                pub.setTelegramMessageId(((Number) resp.get("message_id")).longValue());
                pub.setTelegramChatId(resp.get("chat_id").toString());
                pub.setFechaPublicacion(LocalDateTime.now());
                publicaciones.save(pub);
            });
            log.info("Cola: publicó '{}' en {} (msg {})",
                    p.getTitulo().getTituloOriginal(), canal, resp.get("message_id"));
        } catch (Exception e) {
            log.warn("Cola: el item {} falló: {}", item.getId(), e.getMessage());
            tx().executeWithoutResult(status -> {
                ColaPublicacion c = cola.findById(item.getId()).orElse(null);
                if (c == null) return;
                c.setEstado(ColaPublicacion.ESTADO_FALLO);
                c.setError(e.getMessage());
                c.setUltimoIntento(LocalDateTime.now());
                cola.save(c);
            });
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private Map<String, Object> toMap(ColaPublicacion c) {
        Publicacion p = c.getPublicacion();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("publicacion_id", p.getId());
        m.put("titulo_id", p.getTitulo().getId());
        m.put("titulo", p.getTitulo().getTituloLatino() != null
                ? p.getTitulo().getTituloLatino()
                : p.getTitulo().getTituloOriginal());
        m.put("programado_para", c.getProgramadoPara() == null ? null : c.getProgramadoPara().toString());
        m.put("estado", c.getEstado());
        m.put("modo", c.getModo());
        m.put("error", c.getError());
        m.put("publicado_en", c.getPublicadoEn() == null ? null : c.getPublicadoEn().toString());
        m.put("telegram_message_id", c.getTelegramMessageId());
        m.put("creado_en", c.getCreadoEn() == null ? null : c.getCreadoEn().toString());
        return m;
    }
}