package ar.edu.ofertAR.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * El contenido del correo de recuperación, en HTML con los colores de la app y
 * en texto para los clientes que no muestran HTML. La plantilla usa estilos en
 * línea y tablas porque los clientes de correo descartan casi todo lo demás.
 */
final class PasswordResetEmail {

    static final String SUBJECT = "Tu código para recuperar la contraseña de OfertAR";
    /** Identifica al logo adjunto dentro del HTML. */
    static final String LOGO_ID = "ofertar-logo";
    static final String LOGO_PATH = "mail/logo.png";

    private static final String TEMPLATE = load("mail/password-reset.html");

    private PasswordResetEmail() {}

    /** El nombre lo escribe el usuario: se escapa para que no pueda meter HTML en el correo. */
    static String html(String name, String code, int ttlMinutes) {
        return TEMPLATE
                .replace("{{logo}}", "cid:" + LOGO_ID)
                .replace("{{ttl}}", String.valueOf(ttlMinutes))
                .replace("{{code}}", code)
                .replace("{{name}}", HtmlUtils.htmlEscape(name, StandardCharsets.UTF_8.name()));
    }

    static String text(String name, String code, int ttlMinutes) {
        return """
                Hola %s,

                Tu código para recuperar la contraseña de OfertAR es:

                %s

                Vence en %d minutos. Si no lo pediste vos, ignorá este mensaje: tu contraseña no cambia.
                """.formatted(name, code, ttlMinutes);
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + path, e);
        }
    }
}
