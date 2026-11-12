package ar.edu.ofertAR.exception;

import ar.edu.ofertAR.dto.response.ApiErrorResponse;
<<<<<<< HEAD
=======
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
>>>>>>> dd2bb90 (Merge pull request #30 from LMANMAI/feature/promos-bancarias)
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
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

<<<<<<< HEAD
=======
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

    /**
     * Método equivocado sobre una ruta que existe, como abrir en el navegador
     * un endpoint que es POST. Es un error de quien llama: igual que el 404, en
     * el catch-all saldría como 500 y con el stack trace en el log.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMetodoNoPermitido(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            response.allow(ex.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(
                ApiErrorResponse.builder()
                        .status(405)
                        .message("Método no permitido")
                        .build()
        );
    }

>>>>>>> dd2bb90 (Merge pull request #30 from LMANMAI/feature/promos-bancarias)
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

<<<<<<< HEAD
=======
    /**
     * Uploading several photos of one long receipt is normal usage and can
     * legitimately exceed the multipart limits; surfacing that as a generic
     * 500 gave no clue what went wrong.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Subida rechazada por tamaño: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiErrorResponse.builder()
                        .status(413)
                        .message("Las imágenes son demasiado grandes. Probá sacar menos fotos o con menor resolución.")
                        .build()
        );
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        // Los headers de la excepción (p. ej. Retry-After en un 429) van en la respuesta.
        return ResponseEntity.status(ex.getStatusCode()).headers(ex.getHeaders()).body(
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
>>>>>>> dd2bb90 (Merge pull request #30 from LMANMAI/feature/promos-bancarias)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiErrorResponse.builder()
                        .status(500)
                        .message("Error interno del servidor")
                        .build()
        );
    }
}
