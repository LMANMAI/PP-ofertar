package ar.edu.ofertAR.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * La página a la que lleva el link de referido (OFT-25). Con la app instalada,
 * el botón la abre con el código cargado. Sin la app, el código queda a la vista
 * para tipearlo: fuera de Play Store un link no puede pasarle datos a una
 * instalación nueva.
 *
 * No consulta la base: la respuesta es igual para un código que existe y para
 * uno que no, así la página no sirve para averiguar cuáles son válidos. El
 * código se valida recién al registrarse.
 */
@Hidden
@RestController
public class ReferralLinkController {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{4,12}");

    private static final String PAGE = """
            <!doctype html>
            <html lang="es">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>Te invitaron a OfertAR</title>
            <style>
            body { margin: 0; padding: 32px 16px; background: #0A1F44; color: #fff; text-align: center;
                   font-family: system-ui, sans-serif; }
            main { max-width: 360px; margin: 0 auto; }
            .code { margin: 24px 0; padding: 16px; border-radius: 12px; background: #fff; color: #0A1F44;
                    font-size: 28px; font-weight: 700; letter-spacing: 2px; }
            a { display: block; margin-top: 12px; padding: 14px; border-radius: 12px; background: #7DD4F5;
                color: #0A1F44; font-weight: 700; text-decoration: none; }
            </style>
            </head>
            <body>
            <main>
            <h1>Te invitaron a OfertAR</h1>
            <p>Usá este código cuando te registres:</p>
            <div class="code">%s</div>
            <a href="ofertar://referido/%s">Abrir la app</a>
            %s
            </main>
            </body>
            </html>
            """;

    private final String downloadUrl;

    public ReferralLinkController(@Value("${referral.app-download-url:}") String downloadUrl) {
        this.downloadUrl = downloadUrl == null ? "" : downloadUrl.trim();
    }

    @GetMapping(value = "/r/{code}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String download = downloadUrl.startsWith("https://")
                ? "<a href=\"" + downloadUrl + "\">Bajar la app</a>"
                : "";
        return PAGE.formatted(normalized, normalized, download);
    }
}
