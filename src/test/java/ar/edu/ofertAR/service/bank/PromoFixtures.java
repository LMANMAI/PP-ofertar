package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.service.bank.BankPromotionReader.Promo;
import ar.edu.ofertAR.service.bank.BankPromotionReader.Target;
import ar.edu.ofertAR.service.bank.PromoMatcher.UserContext;
import ar.edu.ofertAR.service.bank.PromoMatcher.UserMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Promos y usuarios de prueba, con la forma de las tablas del scraper (§3 del SPEC). */
final class PromoFixtures {

    private PromoFixtures() {
    }

    static final int TODOS_LOS_DIAS = 0b1111111;
    static final LocalDate DESDE = LocalDate.of(2026, 11, 1);
    static final LocalDate HASTA = LocalDate.of(2026, 11, 30);

    static Target target(String issuer, String network, String cardType) {
        return new Target(issuer, network, cardType, null, null);
    }

    static Target paquete(String issuer, String pkg, String percent) {
        return new Target(issuer, null, null, pkg, new BigDecimal(percent));
    }

    static Builder promo(long id) {
        return new Builder(id);
    }

    static final class Builder {
        private final long id;
        private String retailer = "carrefour";
        private String kind = "percent";
        private BigDecimal percent = new BigDecimal("20.00");
        private Integer installments;
        private BigDecimal amount;
        private String text;
        private int daysMask = TODOS_LOS_DIAS;
        private LocalDate from = DESDE;
        private LocalDate to = HASTA;
        private List<String> regions;
        private List<String> conditions = List.of();
        private List<String> flags = List.of();
        private List<Target> targets = List.of(target(null, null, null));
        private BigDecimal cap;

        Builder(long id) {
            this.id = id;
        }

        Builder retailer(String r) { this.retailer = r; return this; }
        Builder percent(String p) { this.kind = "percent"; this.percent = new BigDecimal(p); return this; }
        Builder cuotas(int n) { this.kind = "installments"; this.percent = null; this.installments = n; return this; }
        Builder monto(String a) { this.kind = "amount"; this.percent = null; this.amount = new BigDecimal(a); return this; }
        Builder text(String t) { this.text = t; return this; }
        Builder dias(int mask) { this.daysMask = mask; return this; }
        Builder vigencia(LocalDate f, LocalDate t) { this.from = f; this.to = t; return this; }
        Builder regiones(String... r) { this.regions = List.of(r); return this; }
        Builder condiciones(String... c) { this.conditions = List.of(c); return this; }
        Builder flags(String... f) { this.flags = List.of(f); return this; }
        Builder targets(Target... t) { this.targets = List.of(t); return this; }
        Builder tope(String c) { this.cap = new BigDecimal(c); return this; }

        Promo build() {
            return new Promo(id, retailer, "Promo " + id, kind, percent, installments, amount, text,
                    cap, cap == null ? null : "month", daysMask, from, to, List.of("store", "online"),
                    null, regions, conditions, "Legal " + id, flags, targets);
        }
    }

    static UserMethod metodo(String issuer, String network, String cardType) {
        String name = switch (issuer) {
            case "banco-galicia" -> "Banco Galicia";
            case "mercado-pago" -> "Mercado Pago";
            case "banco-patagonia" -> "Banco Patagonia";
            default -> issuer;
        };
        String kind = issuer.equals("mercado-pago") ? "wallet" : "bank";
        return new UserMethod(issuer, name, kind, network, cardType);
    }

    static UserContext usuario(List<UserMethod> methods, Set<String> benefits, String province) {
        return new UserContext(methods, benefits, province == null ? Set.of() : Set.of(province));
    }

    static UserContext usuario(UserMethod... methods) {
        return new UserContext(List.of(methods), Set.of(), Set.of());
    }
}
