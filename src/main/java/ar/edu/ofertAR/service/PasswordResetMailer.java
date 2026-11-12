package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

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

    /** Spring no dice nada al configurar el correo, y un SMTP mal cargado recién
     * se notaría cuando alguien pide un código. Corre aparte para no demorar el
     * arranque con el timeout de la conexión. */
    @EventListener(ApplicationReadyEvent.class)
    public void reportMailStatus() {
        CompletableFuture.runAsync(this::checkConnection);
    }

    void checkConnection() {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("SMTP no configurado (spring.mail.host): los códigos de recuperación no se van a enviar");
            return;
        }
        if (!(sender instanceof JavaMailSenderImpl smtp)) return;
        try {
            smtp.testConnection();
            log.info("SMTP conectado a {}:{}, remitente {}", smtp.getHost(), smtp.getPort(), from);
        } catch (MessagingException e) {
            log.error("SMTP configurado en {}:{}, pero no se pudo conectar: {}", smtp.getHost(), smtp.getPort(), e.getMessage());
        }
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

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "OfertAR");
            helper.setTo(user.getEmail());
            helper.setSubject(PasswordResetEmail.SUBJECT);
            helper.setText(
                    PasswordResetEmail.text(user.getName(), code, ttlMinutes),
                    PasswordResetEmail.html(user.getName(), code, ttlMinutes));
            helper.addInline(PasswordResetEmail.LOGO_ID, new ClassPathResource(PasswordResetEmail.LOGO_PATH), "image/png");
            sender.send(message);
            log.info("Código de recuperación enviado al usuario {}", user.getId());
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.error("No se pudo enviar el código de recuperación al usuario {}: {}", user.getId(), e.getMessage());
        }
    }
}
