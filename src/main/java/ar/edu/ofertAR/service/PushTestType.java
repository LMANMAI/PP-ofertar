package ar.edu.ofertAR.service;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Los avisos que se pueden probar desde {@code POST /push/test}. El {@code data}
 * de cada uno es el mismo del aviso real (ver {@link PushNotificationService}),
 * así tocar la prueba abre la misma pantalla que abriría el aviso de verdad.
 */
public enum PushTestType {

    TEST("test",
            "Notificación de prueba",
            "Si ves esto, las notificaciones de OfertAR funcionan en este dispositivo.",
            Map.of()),
    FOLLOWED_PRODUCTS("followedProducts",
            "Prueba: un producto que seguís está en promo",
            "Así te avisamos cuando un producto que seguís entra en promo. Tocá para ver tus productos.",
            PushNotificationService.DATA_FOLLOWED_PRODUCTS),
    OFFERS("offers",
            "Prueba: nueva oferta en tu súper",
            "Así te avisamos de una oferta nueva en tus súper favoritos. Tocá para ver las ofertas.",
            PushNotificationService.DATA_OFFERS),
    REACTIVATION("reactivation",
            "Prueba: te extrañamos",
            "Así te recordamos escanear un ticket cuando hace rato que no lo hacés. Tocá para escanear.",
            PushNotificationService.DATA_REACTIVATION);

    /** Valores aceptados en el cuerpo, para los mensajes de error y el contrato. */
    public static final String VALORES = Arrays.stream(values()).map(PushTestType::wire).collect(Collectors.joining(", "));

    private final String wire;
    private final String title;
    private final String body;
    private final Map<String, String> data;

    PushTestType(String wire, String title, String body, Map<String, String> data) {
        this.wire = wire;
        this.title = title;
        this.body = body;
        this.data = data;
    }

    public String wire() { return wire; }
    public String title() { return title; }
    public String body() { return body; }
    public Map<String, String> data() { return data; }

    /** Sin tipo es {@link #TEST}. Uno desconocido es un error del cliente (400). */
    public static PushTestType fromWire(String value) {
        if (value == null || value.isBlank()) {
            return TEST;
        }
        return Arrays.stream(values())
                .filter(t -> t.wire.equals(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tipo de notificación de prueba inválido: '" + value + "'. Valores posibles: " + VALORES + "."));
    }
}
