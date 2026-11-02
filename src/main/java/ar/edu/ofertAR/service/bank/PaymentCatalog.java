package ar.edu.ofertAR.service.bank;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La parte fija del catálogo de "Mis tarjetas": redes, tipos y beneficios. Los
 * emisores no están acá porque salen de payment_issuers, la tabla del scraper
 * (ver {@link PaymentIssuerReader}).
 *
 * <p>Los slugs son los mismos que usa el scraper en bank_promotion_targets
 * (network, card_type) y en bank_promotions.conditions: si se agrega uno allá,
 * hay que sumarlo acá o el usuario no lo va a poder declarar.
 */
public final class PaymentCatalog {

    private PaymentCatalog() {
    }

    public record Option(String slug, String name) {
    }

    /** {@code reason} es el matchReason de una promo que lo pide cuando el usuario no lo declaró. */
    public record Benefit(String slug, String name, String description, String reason) {
    }

    public static final List<Option> NETWORKS = List.of(
            new Option("visa", "Visa"),
            new Option("mastercard", "Mastercard"),
            new Option("amex", "American Express"),
            new Option("cabal", "Cabal"),
            new Option("naranja", "Naranja"),
            new Option("diners", "Diners Club"));

    /** "wallet" está para las billeteras (Mercado Pago, MODO...): la app puede darlas de
     * alta sin tipo, y el match las toma como "wallet" (ver {@link PromoMatcher}). */
    public static final List<Option> CARD_TYPES = List.of(
            new Option("credit", "Crédito"),
            new Option("debit", "Débito"),
            new Option("prepaid", "Prepaga"),
            new Option("wallet", "Billetera virtual"));

    public static final List<Benefit> BENEFITS = List.of(
            new Benefit("jubilado", "Jubilado/a o pensionado/a",
                    "Cobrás tu jubilación o pensión en el banco.", "Para jubilados"),
            new Benefit("empleado_publico", "Empleado/a público/a",
                    "Cobrás tu sueldo como empleado/a público/a.", "Para empleados públicos"),
            new Benefit("plan_sueldo", "Plan sueldo",
                    "Cobrás tu sueldo en una cuenta del banco.", "Con plan sueldo"),
            new Benefit("club_la_nacion", "Club La Nación",
                    "Tenés la credencial de Club La Nación.", "Con Club La Nación"),
            new Benefit("jumbo_mas", "Jumbo+",
                    "Sos parte del programa Jumbo+.", "Con Jumbo+"),
            new Benefit("mi_carrefour", "Mi Carrefour",
                    "Estás adherido/a a Mi Carrefour.", "Con Mi Carrefour"));

    /** Las cadenas de bank_promotions.retailer con su nombre para mostrar. */
    private static final Map<String, String> RETAILERS = new LinkedHashMap<>();

    static {
        RETAILERS.put("carrefour", "Carrefour");
        RETAILERS.put("jumbo", "Jumbo");
        RETAILERS.put("disco", "Disco");
        RETAILERS.put("vea", "Vea");
        RETAILERS.put("dia", "Día");
        RETAILERS.put("coto", "Coto");
        RETAILERS.put("makro", "Makro");
    }

    public static boolean isNetwork(String slug) {
        return NETWORKS.stream().anyMatch(o -> o.slug().equals(slug));
    }

    public static boolean isCardType(String slug) {
        return CARD_TYPES.stream().anyMatch(o -> o.slug().equals(slug));
    }

    public static boolean isBenefit(String slug) {
        return BENEFITS.stream().anyMatch(b -> b.slug().equals(slug));
    }

    public static String networkName(String slug) {
        return NETWORKS.stream().filter(o -> o.slug().equals(slug)).map(Option::name).findFirst()
                .orElse(capitalize(slug));
    }

    /** "crédito", "débito": en minúscula, para armar "Pide crédito". */
    public static String cardTypeWord(String slug) {
        return switch (slug) {
            case "credit" -> "crédito";
            case "debit" -> "débito";
            case "prepaid" -> "prepaga";
            case "wallet" -> "billetera virtual";
            default -> slug;
        };
    }

    /** matchReason para una condición que el usuario no declaró. */
    public static String conditionReason(String slug) {
        return BENEFITS.stream().filter(b -> b.slug().equals(slug)).map(Benefit::reason).findFirst()
                .orElse("Con requisitos especiales");
    }

    public static String retailerName(String slug) {
        String name = RETAILERS.get(slug);
        return name != null ? name : capitalize(slug);
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
