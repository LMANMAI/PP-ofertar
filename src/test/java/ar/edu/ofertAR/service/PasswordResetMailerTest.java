package ar.edu.ofertAR.service;

import ar.edu.ofertAR.model.User;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetMailerTest {

    private static final String CODE = "482917";

    @SuppressWarnings("unchecked")
    private static PasswordResetMailer mailerWith(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new PasswordResetMailer(provider, "prueba@ofertar.test", false);
    }

    private static User user(String name) {
        User user = new User();
        user.setId(1L);
        user.setName(name);
        user.setEmail("usuario@ofertar.test");
        return user;
    }

    /** Manda el correo y devuelve lo que le llegó al servidor SMTP. */
    private static MimeMessage send(String name) throws MessagingException, IOException {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        mailerWith(sender).sendCode(user(name), CODE, 15);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());

        // Serializado y vuelto a leer, como lo recibe el servidor.
        MimeMessage message = sent.getValue();
        message.saveChanges();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        message.writeTo(wire);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(wire.toByteArray()));
    }

    private static void collectParts(Part part, List<Part> out) throws MessagingException, IOException {
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collectParts(child, out);
            }
        } else {
            out.add(part);
        }
    }

    private static Part partOf(MimeMessage message, String type) throws MessagingException, IOException {
        List<Part> parts = new ArrayList<>();
        collectParts(message, parts);
        for (Part part : parts) {
            if (part.isMimeType(type)) return part;
        }
        return fail("No hay una parte " + type);
    }

    @Test
    @DisplayName("el correo trae HTML con el código y el logo, y texto para quien no ve HTML")
    void sendsHtmlAndText() throws Exception {
        MimeMessage message = send("Ana");

        String html = (String) partOf(message, "text/html").getContent();
        assertTrue(html.contains(CODE));
        assertTrue(html.contains("Hola Ana"));
        assertTrue(html.contains("#0A1F44"));
        assertTrue(html.contains("cid:" + PasswordResetEmail.LOGO_ID));
        assertFalse(html.contains("{{"), "quedó un marcador sin reemplazar");

        String text = (String) partOf(message, "text/plain").getContent();
        assertTrue(text.contains(CODE));
        assertFalse(text.contains("<"));

        String[] ids = partOf(message, "image/png").getHeader("Content-ID");
        assertEquals("<" + PasswordResetEmail.LOGO_ID + ">", ids[0]);
    }

    @Test
    @DisplayName("sale a nombre de OfertAR, desde el remitente configurado y para el usuario")
    void addresses() throws Exception {
        MimeMessage message = send("Ana");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertEquals("OfertAR", from.getPersonal());
        assertEquals("prueba@ofertar.test", from.getAddress());
        assertEquals("usuario@ofertar.test", message.getAllRecipients()[0].toString());
        assertEquals(PasswordResetEmail.SUBJECT, message.getSubject());
    }

    @Test
    @DisplayName("un nombre con HTML no entra como HTML en el correo")
    void escapesTheName() throws Exception {
        String html = (String) partOf(send("<img src=x>{{code}}"), "text/html").getContent();
        assertFalse(html.contains("<img src=x>"));
        assertTrue(html.contains("&lt;img src=x&gt;"));
    }

    @Test
    @DisplayName("un acento del nombre llega como acento")
    void keepsAccents() throws Exception {
        String html = (String) partOf(send("Martín Ñandú"), "text/html").getContent();
        assertTrue(html.contains("Martín Ñandú"));
    }

    @Test
    @DisplayName("un error de envío no llega a quien pidió el código")
    void sendFailureDoesNotPropagate() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("caído")).when(sender).send(any(MimeMessage.class));
        assertDoesNotThrow(() -> mailerWith(sender).sendCode(user("Ana"), CODE, 15));
    }

    @Test
    @DisplayName("sin SMTP configurado el chequeo no falla")
    void withoutSmtp() {
        assertDoesNotThrow(() -> mailerWith(null).checkConnection());
    }

    @Test
    @DisplayName("con SMTP configurado prueba la conexión")
    void testsTheConnection() throws MessagingException {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        mailerWith(sender).checkConnection();
        verify(sender).testConnection();
    }

    @Test
    @DisplayName("una conexión rechazada se registra sin voltear el arranque")
    void connectionFailureDoesNotPropagate() throws MessagingException {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        doThrow(new MessagingException("535 credenciales inválidas")).when(sender).testConnection();
        assertDoesNotThrow(() -> mailerWith(sender).checkConnection());
    }
}
