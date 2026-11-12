package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.service.bank.BankPromotionReader.Promo;
import ar.edu.ofertAR.service.bank.BankPromotionReader.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * La regla de match de la §4 del SPEC de promos bancarias: si una promo le sirve
 * al usuario con lo que declaró en "Mis tarjetas".
 *
 * <ul>
 *   <li><b>all</b>: la promo tiene un target abierto (sin emisor, red ni tipo) y
 *       no está marcada {@code issuer_unknown}. Un emisor que el scraper no pudo
 *       inferir no es "todos los medios": ésas dan {@code none}.</li>
 *   <li><b>match</b>: algún medio del usuario cumple algún target (emisor igual
 *       o target sin emisor; red igual o target sin red; tipo igual o target sin
 *       tipo).</li>
 *   <li><b>near</b>: el emisor coincide pero no la red o el tipo ("Pide
 *       crédito"), o cumple el medio pero le falta una condición que no declaró
 *       ("Para jubilados") o la promo es de otra provincia ("Sólo en Córdoba").
 *       Sin provincia del usuario, una promo regional nunca da match (D7).</li>
 *   <li><b>none</b>: nada de lo anterior.</li>
 * </ul>
 *
 * <p>El paquete de cuenta (Patagonia Plus, etc.) no se pide al usuario, así que no
 * entra en el match: se informa en {@code requires.package} y {@code percentRange}.
 */
public final class PromoMatcher {

    private PromoMatcher() {
    }

    public static final String MATCH = "match";
    public static final String NEAR = "near";
    public static final String ALL = "all";
    public static final String NONE = "none";

    public static final String ISSUER_UNKNOWN = "issuer_unknown";

    /** Un medio del usuario, con el nombre y el tipo de su emisor (del catálogo). */
    public record UserMethod(String issuerSlug, String issuerName, String issuerKind, String network,
                             String cardType) {

        /** Una billetera cargada sin tipo cuenta como "wallet", que es como la marca el scraper. */
        String effectiveCardType() {
            if (cardType == null && "wallet".equals(issuerKind)) return "wallet";
            return cardType;
        }
    }

    /** Lo que el usuario declaró. {@code provinces} son canónicas ({@link Provincias}); vacío = sin zona. */
    public record UserContext(List<UserMethod> methods, Set<String> benefits, Set<String> provinces) {
    }

    public record Result(String match, String reason) {
    }

    public static Result evaluate(Promo promo, UserContext user) {
        List<Target> targets = usableTargets(promo);
        String personal = personalGate(promo, user);

        if (opensToAll(promo)) {
            return personal == null ? new Result(ALL, null) : new Result(NEAR, personal);
        }

        for (UserMethod m : user.methods()) {
            for (Target t : targets) {
                if (issuerFits(m, t) && networkFits(m, t) && cardTypeFits(m, t)) {
                    return personal == null ? new Result(MATCH, "Con tu " + label(m)) : new Result(NEAR, personal);
                }
            }
        }

        // Near-miss: el emisor coincide pero falta la red o el tipo. Se elige el
        // par con menos diferencias, para decir lo mínimo que le falta.
        String best = null;
        int bestMisses = Integer.MAX_VALUE;
        for (UserMethod m : user.methods()) {
            for (Target t : targets) {
                if (!issuerFits(m, t)) continue;
                List<String> missing = new ArrayList<>();
                if (!networkFits(m, t)) missing.add(PaymentCatalog.networkName(t.network()));
                if (!cardTypeFits(m, t)) missing.add(PaymentCatalog.cardTypeWord(t.cardType()));
                if (missing.size() < bestMisses) {
                    bestMisses = missing.size();
                    best = "Pide " + String.join(" ", missing);
                }
            }
        }
        return best != null ? new Result(NEAR, best) : new Result(NONE, null);
    }

    /** Hay un target sin emisor, red ni tipo y el emisor no es desconocido. */
    public static boolean opensToAll(Promo promo) {
        if (isIssuerUnknown(promo)) return false;
        return promo.targets().stream()
                .anyMatch(t -> t.issuerSlug() == null && t.network() == null && t.cardType() == null);
    }

    public static boolean isIssuerUnknown(Promo promo) {
        return promo.qualityFlags().contains(ISSUER_UNKNOWN);
    }

    /**
     * Los targets que sirven para matchear. Con {@code issuer_unknown} los que no
     * tienen emisor no significan "cualquiera" sino "no sabemos cuál", así que se
     * descartan.
     */
    public static List<Target> usableTargets(Promo promo) {
        if (!isIssuerUnknown(promo)) return promo.targets();
        return promo.targets().stream().filter(t -> t.issuerSlug() != null).toList();
    }

    /** Null si la persona cumple condiciones y zona; si no, la razón para matchReason. */
    static String personalGate(Promo promo, UserContext user) {
        for (String condition : promo.conditions()) {
            if (!user.benefits().contains(condition)) {
                return PaymentCatalog.conditionReason(condition);
            }
        }
        List<String> regions = regions(promo);
        if (regions != null && regions.stream().noneMatch(user.provinces()::contains)) {
            return Provincias.soloEn(regions);
        }
        return null;
    }

    /** Las regiones canónicas de la promo, o null si es para todo el país. */
    public static List<String> regions(Promo promo) {
        if (promo.regions() == null || promo.regions().isEmpty()) return null;
        return promo.regions().stream().map(Provincias::normalizarRegion).distinct().toList();
    }

    private static boolean issuerFits(UserMethod m, Target t) {
        return t.issuerSlug() == null || t.issuerSlug().equals(m.issuerSlug());
    }

    private static boolean networkFits(UserMethod m, Target t) {
        return t.network() == null || t.network().equals(m.network());
    }

    private static boolean cardTypeFits(UserMethod m, Target t) {
        return t.cardType() == null || t.cardType().equals(m.effectiveCardType());
    }

    /** "Banco Galicia Visa", "Mercado Pago". */
    private static String label(UserMethod m) {
        String name = Objects.requireNonNullElse(m.issuerName(), m.issuerSlug());
        return m.network() == null ? name : name + " " + PaymentCatalog.networkName(m.network());
    }
}
