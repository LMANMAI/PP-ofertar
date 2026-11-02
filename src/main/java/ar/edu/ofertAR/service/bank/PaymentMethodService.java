package ar.edu.ofertAR.service.bank;

import ar.edu.ofertAR.dto.request.UpdatePaymentMethodsRequest;
import ar.edu.ofertAR.dto.response.MyPaymentMethodsResponse;
import ar.edu.ofertAR.dto.response.MyPaymentMethodsResponse.PaymentMethodView;
import ar.edu.ofertAR.dto.response.PaymentCatalogResponse;
import ar.edu.ofertAR.dto.response.PaymentCatalogResponse.CatalogBenefit;
import ar.edu.ofertAR.dto.response.PaymentCatalogResponse.CatalogOption;
import ar.edu.ofertAR.dto.response.PaymentIssuerView;
import ar.edu.ofertAR.model.User;
import ar.edu.ofertAR.model.UserBenefit;
import ar.edu.ofertAR.model.UserPaymentMethod;
import ar.edu.ofertAR.repository.UserBenefitRepository;
import ar.edu.ofertAR.repository.UserPaymentMethodRepository;
import ar.edu.ofertAR.service.bank.PaymentIssuerReader.Issuer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Mis tarjetas" (OFT-51): el catálogo y los medios de pago y beneficios que
 * declara el usuario. Sólo emisor, red y tipo: nunca números de tarjeta.
 */
@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private final UserPaymentMethodRepository methodRepository;
    private final UserBenefitRepository benefitRepository;
    private final PaymentIssuerReader issuerReader;

    public PaymentCatalogResponse catalog() {
        return new PaymentCatalogResponse(
                issuerReader.findAll().stream().map(PaymentMethodService::view).toList(),
                PaymentCatalog.NETWORKS.stream().map(o -> new CatalogOption(o.slug(), o.name())).toList(),
                PaymentCatalog.CARD_TYPES.stream().map(o -> new CatalogOption(o.slug(), o.name())).toList(),
                PaymentCatalog.BENEFITS.stream()
                        .map(b -> new CatalogBenefit(b.slug(), b.name(), b.description())).toList());
    }

    @Transactional(readOnly = true)
    public MyPaymentMethodsResponse get(User user) {
        return response(user, issuersBySlug(issuerReader.findAll()));
    }

    /**
     * Reemplaza el conjunto completo. Los medios que ya estaban conservan su id
     * (la app puede tener uno abierto para "Quitar"); se borran los que no vienen
     * y se agregan los nuevos. Un slug que no está en el catálogo es un 400 y no
     * se guarda nada.
     */
    @Transactional
    public MyPaymentMethodsResponse replace(User user, UpdatePaymentMethodsRequest request) {
        List<Issuer> catalogIssuers = issuerReader.findAll();
        if (catalogIssuers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El catálogo de bancos y billeteras no está disponible. Probá de nuevo más tarde.");
        }
        Map<String, Issuer> issuers = issuersBySlug(catalogIssuers);

        List<String> errors = new ArrayList<>();
        Set<MethodKey> wanted = new LinkedHashSet<>();
        for (UpdatePaymentMethodsRequest.PaymentMethodInput input : request.getMethods()) {
            MethodKey key = new MethodKey(slug(input.getIssuerSlug()), slug(input.getNetwork()), slug(input.getCardType()));
            if (!issuers.containsKey(key.issuerSlug())) {
                errors.add("El emisor '" + key.issuerSlug() + "' no está en el catálogo");
            }
            if (key.network() != null && !PaymentCatalog.isNetwork(key.network())) {
                errors.add("La red '" + key.network() + "' no está en el catálogo");
            }
            if (key.cardType() != null && !PaymentCatalog.isCardType(key.cardType())) {
                errors.add("El tipo '" + key.cardType() + "' no está en el catálogo");
            }
            wanted.add(key);
        }
        Set<String> wantedBenefits = new LinkedHashSet<>();
        for (String benefit : request.getBenefits()) {
            String b = slug(benefit);
            if (!PaymentCatalog.isBenefit(b)) {
                errors.add("El beneficio '" + b + "' no está en el catálogo");
            }
            wantedBenefits.add(b);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(". ", errors.stream().distinct().toList()));
        }

        List<UserPaymentMethod> current = methodRepository.findByUserIdOrderByCreatedAtAscIdAsc(user.getId());
        Set<MethodKey> kept = new LinkedHashSet<>();
        List<UserPaymentMethod> toDelete = new ArrayList<>();
        for (UserPaymentMethod m : current) {
            MethodKey key = new MethodKey(m.getIssuerSlug(), m.getNetwork(), m.getCardType());
            // kept.add falso = fila repetida (la clave única no frena NULL repetidos): sobra.
            if (wanted.contains(key) && kept.add(key)) continue;
            toDelete.add(m);
        }
        if (!toDelete.isEmpty()) {
            methodRepository.deleteAll(toDelete);
            // Los DELETE antes que los INSERT: Hibernate ordena los insert primero
            // dentro de un flush (ver FavoriteStoreService).
            methodRepository.flush();
        }
        List<UserPaymentMethod> toInsert = wanted.stream()
                .filter(k -> !kept.contains(k))
                .map(k -> UserPaymentMethod.builder()
                        .user(user)
                        .issuerSlug(k.issuerSlug())
                        .network(k.network())
                        .cardType(k.cardType())
                        .build())
                .toList();
        if (!toInsert.isEmpty()) {
            methodRepository.saveAll(toInsert);
        }

        List<UserBenefit> currentBenefits = benefitRepository.findByUserIdOrderByBenefitAsc(user.getId());
        List<UserBenefit> benefitsToDelete = currentBenefits.stream()
                .filter(b -> !wantedBenefits.contains(b.getBenefit()))
                .toList();
        if (!benefitsToDelete.isEmpty()) {
            benefitRepository.deleteAll(benefitsToDelete);
            benefitRepository.flush();
        }
        Set<String> had = currentBenefits.stream().map(UserBenefit::getBenefit).collect(Collectors.toSet());
        List<UserBenefit> benefitsToInsert = wantedBenefits.stream()
                .filter(b -> !had.contains(b))
                .map(b -> UserBenefit.builder().userId(user.getId()).benefit(b).build())
                .toList();
        if (!benefitsToInsert.isEmpty()) {
            benefitRepository.saveAll(benefitsToInsert);
        }

        return response(user, issuers);
    }

    /** 404 si no existe o si es de otro usuario: no se distingue, para no revelar ids ajenos. */
    @Transactional
    public void delete(User user, Long id) {
        UserPaymentMethod method = methodRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No encontramos ese medio de pago"));
        methodRepository.delete(method);
    }

    /** Lo que necesita el match: los medios con el nombre y tipo de su emisor, y los beneficios. */
    @Transactional(readOnly = true)
    public List<PromoMatcher.UserMethod> userMethods(User user, Map<String, Issuer> issuers) {
        return methodRepository.findByUserIdOrderByCreatedAtAscIdAsc(user.getId()).stream()
                .map(m -> {
                    Issuer issuer = issuers.get(m.getIssuerSlug());
                    return new PromoMatcher.UserMethod(m.getIssuerSlug(),
                            issuer != null ? issuer.name() : m.getIssuerSlug(),
                            issuer != null ? issuer.kind() : null,
                            m.getNetwork(), m.getCardType());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Set<String> userBenefits(User user) {
        return benefitRepository.findByUserIdOrderByBenefitAsc(user.getId()).stream()
                .map(UserBenefit::getBenefit)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Map<String, Issuer> issuersBySlug(List<Issuer> issuers) {
        return issuers.stream().collect(Collectors.toMap(Issuer::slug, Function.identity(), (a, b) -> a));
    }

    private MyPaymentMethodsResponse response(User user, Map<String, Issuer> issuers) {
        List<PaymentMethodView> methods = methodRepository.findByUserIdOrderByCreatedAtAscIdAsc(user.getId()).stream()
                .map(m -> new PaymentMethodView(
                        m.getId(),
                        issuerView(m.getIssuerSlug(), issuers),
                        m.getNetwork(),
                        m.getCardType()))
                .toList();
        List<String> benefits = benefitRepository.findByUserIdOrderByBenefitAsc(user.getId()).stream()
                .map(UserBenefit::getBenefit)
                .toList();
        return new MyPaymentMethodsResponse(methods, benefits);
    }

    /**
     * Si el emisor ya no figura en el catálogo (el scraper lo renombró o la tabla
     * no se pudo leer) se devuelve igual, con el slug como nombre, para que el
     * usuario lo vea y lo pueda quitar.
     */
    private static PaymentIssuerView issuerView(String slug, Map<String, Issuer> issuers) {
        Issuer issuer = issuers.get(slug);
        return issuer != null ? view(issuer) : new PaymentIssuerView(slug, slug, "bank");
    }

    private static PaymentIssuerView view(Issuer issuer) {
        return new PaymentIssuerView(issuer.slug(), issuer.name(), issuer.kind());
    }

    /** Recortado y en minúscula; vacío cuenta como no indicado. */
    private static String slug(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record MethodKey(String issuerSlug, String network, String cardType) {
        MethodKey {
            Objects.requireNonNull(issuerSlug, "issuerSlug");
        }
    }
}
