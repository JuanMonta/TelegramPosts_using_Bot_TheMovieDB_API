package api.crow.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Manejador global de excepciones. Conserva la forma de respuesta que el
 * frontend ya parsea ({"error": "<mensaje>"}, ver ApiService.mensajeDeError).
 * Nunca devuelve texto crudo de excepciones internas al cliente: los errores
 * no controlados se registran en el servidor y responden un 500 genérico.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> argumentoInvalido(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, mensajeUsuario(e));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> estadoInvalido(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, mensajeUsuario(e));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> estadoExplicito(ResponseStatusException e) {
        String mensaje = e.getReason() == null || e.getReason().isBlank()
                ? "La solicitud no se pudo procesar (" + e.getStatusCode().value() + ")."
                : e.getReason();
        HttpStatus estado = HttpStatus.resolve(e.getStatusCode().value());
        return error(estado != null ? estado : HttpStatus.BAD_REQUEST, mensaje);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> archivoGrande(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo supera el límite de tamaño permitido.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> noControlada(Exception e) {
        ResponseStatus anotada = AnnotationUtils.findAnnotation(e.getClass(), ResponseStatus.class);
        if (anotada != null) {
            HttpStatus estado = anotada.code() != HttpStatus.INTERNAL_SERVER_ERROR
                    ? anotada.code() : anotada.value();
            return error(estado, mensajeUsuario(e));
        }
        if (e instanceof ErrorResponse resp) {
            HttpStatus estado = HttpStatus.resolve(resp.getStatusCode().value());
            if (estado != null) {
                return error(estado, "No se pudo procesar la solicitud (" + estado.value() + ").");
            }
        }
        log.error("Error no controlado: {}: {}", e.getClass().getSimpleName(), mensajeUsuario(e), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor.");
    }

    /** Mensaje apto para logs y cliente: de una sola línea, sin cuerpos multilínea. */
    private static String mensajeUsuario(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        return m.replaceAll("\\s+", " ").trim();
    }

    /** Misma forma que el frontend ya parsea: {"error": "<mensaje>"}. */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).body(Map.of("error", mensaje));
    }
}
