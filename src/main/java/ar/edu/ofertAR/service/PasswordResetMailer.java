package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends the recovery code. A mail failure never reaches the caller: the
 * endpoint answers the same whether or not the account exists, so an error
 * here must not turn into a different response.
 */
@Slf4j
@Component
public class PasswordResetMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final boolean devLogCode;

    public PasswordResetMailer(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${password-reset.mail-from:no-reply@ofertar.app}") String from,
            @Value("${password-reset.dev-log-code:false}") boolean devLogCode) {
        this.mailSender = mailSender;
        this.from = from;
        this.devLogCode = devLogCode;
    }

    public void sendCode(User user, String code, int ttlMinutes) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            // JavaMailSender only exists when spring.mail.host is set.
            log.warn("SMTP no configurado (spring.mail.host): no se envió el código de recuperación al usuario {}", user.getId());
            if (devLogCode) {
                log.warn("[DEV] código de recuperación del usuario {}: {}", user.getId(), code);
            }
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("Tu código para recuperar la contraseña de OfertAR");
        message.setText("""
                Hola %s,

                Tu código para recuperar la contraseña de OfertAR es:

                %s

                Vence en %d minutos. Si no lo pediste vos, ignorá este mensaje: tu contraseña no cambia.
                """.formatted(user.getName(), code, ttlMinutes));
        try {
            sender.send(message);
        } catch (MailException e) {
            log.error("No se pudo enviar el código de recuperación al usuario {}: {}", user.getId(), e.getMessage());
        }
    }
}
