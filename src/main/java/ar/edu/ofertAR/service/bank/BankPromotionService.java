package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.dto.response.BankPromotionsTodayResponse;
import ar.edu.ofertAR.dto.response.BankPromotionsWeekResponse;
import ar.edu.ofertAR.dto.response.BankPromotionsWeekResponse.PromoDay;
import ar.edu.ofertAR.dto.response.BankPromotionsWeekResponse.PromoNote;
import ar.edu.ofertAR.dto.response.PromoView;
import ar.edu.ofertAR.dto.response.PromoView.PercentRange;
import ar.edu.ofertAR.dto.response.PromoView.PromoCap;
import ar.edu.ofertAR.dto.response.PromoView.PromoDiscount;
import ar.edu.ofertAR.dto.response.PromoView.PromoIssuer;
import ar.edu.ofertAR.dto.response.PromoView.PromoRequires;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.service.bank.BankPromotionReader.Promo;
import ar.edu.ofertAR.service.bank.BankPromotionReader.Target;
import ar.edu.ofertAR.service.bank.PaymentIssuerReader.Issuer;
import ar.edu.ofertAR.service.bank.PromoMatcher.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * El calendario semanal y la tira de Home de promos bancarias (OFT-52), con la
 * regla de match de {@link PromoMatcher} y el orden de la §4 del SPEC.
 */
@Service
public class BankPromotionService {

    public static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    static final int DAYS_IN_WEEK = 7;
    static final int TODAY_LIMIT = 3;
    public static final String ROLLOVER = "rollover";

    private final BankPromotionReader promotionReader;
    private final PaymentIssuerReader issuerReader;
    private final PaymentMethodService paymentMethodService;
    private final Clock clock;

    @Autowired
    public BankPromotionService(BankPromotionReader promotionReader, PaymentIssuerReader issuerReader,
                                PaymentMethodService paymentMethodService) {
        this(promotionReader, issuerReader, paymentMethodService, Clock.system(ARGENTINA));
    }

    BankPromotionService(BankPromotionReader promotionReader, PaymentIssuerReader issuerReader,
                         PaymentMethodService paymentMethodService, Clock clock) {
        this.promotionReader = promotionReader;
        this.issuerReader = issuerReader;
        this.paymentMethodService = paymentMethodService;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ARGENTINA));
    }

    /**
     * @param from     primer día; null = hoy en Argentina
     * @param province zona del usuario tal como la manda la app; null = sin zona.
     *                 Si no se reconoce es un 400.
     */
    public BankPromotionsWeekResponse week(User user, LocalDate from, boolean mine, String province) {
        LocalDate start = from != null ? from : today();
        LocalDate end = start.plusDays(DAYS_IN_WEEK - 1L);
        Session session = session(user, province);
        // Desde el día anterior, para ver si algo venció justo antes de un día 1 (rollover).
        List<Promo> promos = promotionReader.findValidBetween(start.minusDays(1), end);
        Map<Long, PromoView> views = views(promos, session);

        List<PromoDay> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(day(d, promos, views, mine));
        }
        return new BankPromotionsWeekResponse(start, end, mine, session.hasPaymentMethods(), days,
                rolloverNotes(start, end, promos));
    }

    public BankPromotionsTodayResponse today(User user, boolean mine, String province) {
        LocalDate date = today();
        Session session = session(user, province);
        List<Promo> promos = promotionReader.findValidBetween(date, date);
        PromoDay day = day(date, promos, views(promos, session), mine);
        return new BankPromotionsTodayResponse(date, day.bestPercent(),
                day.promos().stream().limit(TODAY_LIMIT).toList());
    }

    private record Session(UserContext context, Map<String, Issuer> issuers, boolean hasPaymentMethods) {
    }

    private Session session(User user, String province) {
        Set<String> provinces = Provincias.zona(province);
        Map<String, Issuer> issuers = PaymentMethodService.issuersBySlug(issuerReader.findAll());
        List<PromoMatcher.UserMethod> methods = paymentMethodService.userMethods(user, issuers);
        UserContext context = new UserContext(methods, paymentMethodService.userBenefits(user), provinces);
        return new Session(context, issuers, !methods.isEmpty());
    }

    private static Map<Long, PromoView> views(List<Promo> promos, Session session) {
        return promos.stream().collect(Collectors.toMap(
                Promo::id, p -> toView(p, session.context(), session.issuers()), (a, b) -> a));
    }

    /** Las promos del día, filtradas (con mine=true se van las none) y ordenadas. */
    static PromoDay day(LocalDate date, List<Promo> promos, Map<Long, PromoView> views, boolean mine) {
        List<PromoView> list = promos.stream()
                .filter(p -> p.appliesOn(date))
                .map(p -> views.get(p.id()))
                .filter(v -> !mine || !PromoMatcher.NONE.equals(v.match()))
                .sorted(ORDER)
                .toList();
        BigDecimal best = list.stream()
                .filter(v -> !mine || usable(v))
                .map(BankPromotionService::bestPercent)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new PromoDay(date, date.getDayOfWeek().getValue(), best, list);
    }

    private static boolean usable(PromoView v) {
        return PromoMatcher.MATCH.equals(v.match()) || PromoMatcher.ALL.equals(v.match());
    }

    /**
     * match y all primero, después near, al final none. En cada grupo los % de
     * mayor a menor y las cuotas y montos al final (D8); el resto, estable por
     * cadena e id.
     */
    static final Comparator<PromoView> ORDER = Comparator
            .comparingInt(BankPromotionService::group)
            .thenComparingInt(v -> kindRank(v.discount().kind()))
            .thenComparing(BankPromotionService::magnitude, Comparator.reverseOrder())
            .thenComparing(PromoView::retailer)
            .thenComparingLong(PromoView::id);

    private static int group(PromoView v) {
        return switch (v.match()) {
            case PromoMatcher.MATCH, PromoMatcher.ALL -> 0;
            case PromoMatcher.NEAR -> 1;
            default -> 2;
        };
    }

    private static int kindRank(String kind) {
        return switch (kind) {
            case "percent" -> 0;
            case "installments" -> 1;
            case "amount" -> 2;
            default -> 3;
        };
    }

    /** Lo que se compara dentro del mismo tipo: el %, las cuotas o el monto. */
    private static BigDecimal magnitude(PromoView v) {
        PromoDiscount d = v.discount();
        BigDecimal value = switch (d.kind()) {
            case "percent" -> bestPercent(v);
            case "installments" -> d.installments() == null ? null : BigDecimal.valueOf(d.installments());
            case "amount" -> d.amount();
            default -> null;
        };
        return value != null ? value : BigDecimal.ZERO;
    }

    /** El % más alto que ofrece la promo (con variantes por paquete, el del mejor paquete). */
    static BigDecimal bestPercent(PromoView v) {
        if (!"percent".equals(v.discount().kind())) return null;
        BigDecimal base = v.discount().percent();
        BigDecimal top = v.percentRange() != null ? v.percentRange().max() : null;
        if (base == null) return top;
        if (top == null) return base;
        return base.max(top);
    }

    /**
     * Nota de cambio de mes: si en la semana hay un día 1 y el día anterior
     * vencieron promos que no se renovaron (hay menos vigentes el 1 que el día
     * anterior), desde ese día 1 la UI avisa que las del mes nuevo suelen
     * publicarse los primeros días.
     */
    static List<PromoNote> rolloverNotes(LocalDate start, LocalDate end, List<Promo> promos) {
        List<PromoNote> notes = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfMonth() != 1) continue;
            LocalDate eve = d.minusDays(1);
            LocalDate first = d;
            boolean expired = promos.stream().anyMatch(p -> p.validTo().equals(eve));
            long validBefore = promos.stream().filter(p -> p.validOn(eve)).count();
            long validAfter = promos.stream().filter(p -> p.validOn(first)).count();
            if (expired && validAfter < validBefore) {
                notes.add(new PromoNote(ROLLOVER, d));
            }
        }
        return notes;
    }

    static PromoView toView(Promo p, UserContext user, Map<String, Issuer> issuers) {
        List<Target> targets = PromoMatcher.usableTargets(p);
        PromoMatcher.Result result = PromoMatcher.evaluate(p, user);
        PercentRange range = percentRange(p, targets);
        return new PromoView(
                p.id(),
                p.retailer(),
                PaymentCatalog.retailerName(p.retailer()),
                p.title(),
                discount(p),
                p.capAmount() == null ? null : new PromoCap(plain(p.capAmount()), p.capPeriod()),
                p.channels(),
                p.storeFormats(),
                p.validFrom(),
                p.validTo(),
                targets.stream()
                        .map(Target::issuerSlug)
                        .filter(Objects::nonNull)
                        .distinct()
                        .map(slug -> new PromoIssuer(slug,
                                issuers.containsKey(slug) ? issuers.get(slug).name() : slug))
                        .toList(),
                PromoMatcher.opensToAll(p),
                requires(targets),
                range,
                p.conditions(),
                PromoMatcher.regions(p),
                result.match(),
                result.reason(),
                p.legalText(),
                p.qualityFlags());
    }

    static PromoDiscount discount(Promo p) {
        String kind = switch (Objects.requireNonNullElse(p.discountKind(), "other")) {
            case "percent", "installments", "amount" -> p.discountKind();
            default -> "other";
        };
        BigDecimal percent = p.discountPercent() == null ? null : plain(p.discountPercent());
        BigDecimal amount = p.discountAmount() == null ? null : plain(p.discountAmount());
        String text = p.discountText() != null && !p.discountText().isBlank()
                ? p.discountText().trim()
                : switch (kind) {
                    case "percent" -> percent != null ? percent.toPlainString() + "%" : p.title();
                    case "installments" -> p.installments() != null
                            ? p.installments() + " cuotas sin interés" : p.title();
                    case "amount" -> amount != null ? "$ " + pesos(amount) : p.title();
                    default -> p.title();
                };
        return new PromoDiscount(kind, percent, p.installments(), amount, text);
    }

    /** Lo que piden todos los targets por igual; null si no piden nada en común. */
    static PromoRequires requires(List<Target> targets) {
        if (targets.isEmpty()) return null;
        String network = common(targets, Target::network);
        String cardType = common(targets, Target::cardType);
        String pkg = common(targets, Target::packageName);
        if (network == null && cardType == null && pkg == null) return null;
        return new PromoRequires(network, cardType, pkg);
    }

    private static String common(List<Target> targets, Function<Target, String> field) {
        String first = field.apply(targets.get(0));
        if (first == null) return null;
        return targets.stream().allMatch(t -> first.equals(field.apply(t))) ? first : null;
    }

    /**
     * Variantes por paquete (Patagonia 15/20/25/30/35%): el % de cada target, el
     * suyo propio o el de la promo. Null si hay uno solo.
     */
    static PercentRange percentRange(Promo p, List<Target> targets) {
        if (targets.stream().noneMatch(t -> t.percentOverride() != null)) return null;
        List<BigDecimal> sorted = targets.stream()
                .map(t -> t.percentOverride() != null ? t.percentOverride() : p.discountPercent())
                .filter(Objects::nonNull)
                .map(BankPromotionService::plain)
                .sorted()
                .toList();
        if (sorted.isEmpty() || sorted.get(0).compareTo(sorted.get(sorted.size() - 1)) == 0) return null;
        return new PercentRange(sorted.get(0), sorted.get(sorted.size() - 1));
    }

    /** 30.00 → 30, 12.50 → 12.5: sin ceros de más ni notación científica en el JSON. */
    static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String pesos(BigDecimal amount) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-AR"));
        f.setMaximumFractionDigits(2);
        return f.format(amount);
    }
}
