package ar.edu.ofertAR.exception;

import ar.edu.ofertAR.dto.response.ApiErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .status(400)
                        .message("Error de validación")
                        .errors(errors)
                        .build()
        );
    }

    /**
     * Body ausente, JSON malformado o tipo incompatible.
     * Es culpa del cliente, así que 400: devolver 500 le dice "se rompió el
     * servidor" cuando en realidad mandó mal el request.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyIlegible(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .status(400)
                        .message("El cuerpo del request no es un JSON válido o está vacío")
                        .build()
        );
    }

    /** Falta un @RequestParam obligatorio. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleParamFaltante(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .status(400)
                        .message("Falta el parámetro obligatorio '" + ex.getParameterName() + "'")
                        .build()
        );
    }

    /** Parámetro con tipo incompatible, p.ej. /sepa/productos?page=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTipoInvalido(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .status(400)
                        .message("El parámetro '" + ex.getName() + "' tiene un valor inválido")
                        .build()
        );
    }

    /**
     * Ruta inexistente. Sin este handler cae en el catch-all y un 404 sale
     * como 500, que es engañoso para el front y ensucia cualquier métrica
     * de errores del servidor.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRutaInexistente(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiErrorResponse.builder()
                        .status(404)
                        .message("Recurso no encontrado")
                        .build()
        );
    }

    /** Ticket más grande que spring.servlet.multipart.max-file-size. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleArchivoGrande(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiErrorResponse.builder()
                        .status(413)
                        .message("El archivo supera el tamaño máximo permitido")
                        .build()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                ApiErrorResponse.builder()
                        .status(400)
                        .message(ex.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiErrorResponse.builder()
                        .status(401)
                        .message("Email o contraseña incorrectos")
                        .build()
        );
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(
                ApiErrorResponse.builder()
                        .status(ex.getStatusCode().value())
                        .message(ex.getReason())
                        .build()
        );
    }

    /**
     * Último recurso. Al cliente se le sigue ocultando el detalle interno,
     * pero ahora queda en el log: antes un 500 no dejaba ningún rastro y
     * era imposible saber qué había fallado.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        log.error("Error no controlado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.builder()
                        .status(500)
                        .message("Error interno del servidor")
                        .build()
        );
    }
}
