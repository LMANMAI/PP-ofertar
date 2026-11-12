package ar.edu.ofertAR.service.bank;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lee las promos bancarias que escribe el scraper (bank_promotions y
 * bank_promotion_targets, OFT-45). Sólo SELECT; ver {@link PaymentIssuerReader}
 * por qué es SQL a mano y no una entidad JPA.
 *
 * <p>Las promos no se borran nunca en el scraper (upsert sin borrar): las viejas
 * quedan y se filtran por vigencia, que es lo que hace la consulta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankPromotionReader {

    /** Una fila de bank_promotions con sus targets. Las listas JSON ya vienen parseadas. */
    public record Promo(
            long id,
            String retailer,
            String title,
            String discountKind,
            BigDecimal discountPercent,
            Integer installments,
            BigDecimal discountAmount,
            String discountText,
            BigDecimal capAmount,
            String capPeriod,
            int daysMask,
            LocalDate validFrom,
            LocalDate validTo,
            List<String> channels,
            /* null = sin dato */ List<String> storeFormats,
            /* null = todo el país */ List<String> regions,
            List<String> conditions,
            String legalText,
            List<String> qualityFlags,
            List<Target> targets
    ) {
        /** bit0 = lunes … bit6 = domingo; {@code weekday} va de 1 (lunes) a 7 (domingo). */
        public boolean appliesOn(LocalDate date) {
            int weekday = date.getDayOfWeek().getValue();
            return !date.isBefore(validFrom) && !date.isAfter(validTo)
                    && (daysMask & (1 << (weekday - 1))) != 0;
        }

        public boolean validOn(LocalDate date) {
            return !date.isBefore(validFrom) && !date.isAfter(validTo);
        }
    }

    /** issuerSlug null = cualquier emisor; network/cardType null = cualquiera. */
    public record Target(String issuerSlug, String network, String cardType, String packageName,
                         BigDecimal percentOverride) {
    }

    private static final String PROMOS = """
            SELECT id, retailer, title, discount_kind, discount_percent, installments, discount_amount,
                   discount_text, cap_amount, cap_period, days_mask, valid_from, valid_to, channels,
                   store_formats, regions, conditions, legal_text, quality_flags
            FROM bank_promotions
            WHERE valid_from <= :hasta AND valid_to >= :desde
            ORDER BY id
            """;

    private static final String TARGETS = """
            SELECT promotion_id, issuer_slug, network, card_type, `package`, percent_override
            FROM bank_promotion_targets
            WHERE promotion_id IN (:ids)
            ORDER BY id
            """;

    private static final TypeReference<List<String>> LISTA = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Las promos vigentes en algún día de [desde, hasta]; vacío si no se pudo leer. */
    public List<Promo> findValidBetween(LocalDate desde, LocalDate hasta) {
        try {
            List<Promo> sinTargets = jdbc.query(PROMOS,
                    new MapSqlParameterSource().addValue("desde", Date.valueOf(desde)).addValue("hasta", Date.valueOf(hasta)),
                    (rs, i) -> promo(rs));
            if (sinTargets.isEmpty()) return List.of();

            Map<Long, List<Target>> targets = new HashMap<>();
            jdbc.query(TARGETS,
                    new MapSqlParameterSource("ids", sinTargets.stream().map(Promo::id).toList()),
                    rs -> {
                        targets.computeIfAbsent(rs.getLong("promotion_id"), k -> new ArrayList<>()).add(new Target(
                                rs.getString("issuer_slug"),
                                rs.getString("network"),
                                rs.getString("card_type"),
                                rs.getString("package"),
                                rs.getBigDecimal("percent_override")));
                    });
            return sinTargets.stream()
                    .map(p -> conTargets(p, targets.getOrDefault(p.id(), List.of())))
                    .toList();
        } catch (DataAccessException e) {
            log.warn("No se pudieron leer las promos bancarias: {}", e.getMessage());
            return List.of();
        }
    }

    private Promo promo(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        return new Promo(
                id,
                rs.getString("retailer"),
                rs.getString("title"),
                rs.getString("discount_kind"),
                rs.getBigDecimal("discount_percent"),
                rs.getObject("installments", Integer.class),
                rs.getBigDecimal("discount_amount"),
                rs.getString("discount_text"),
                rs.getBigDecimal("cap_amount"),
                rs.getString("cap_period"),
                rs.getInt("days_mask"),
                rs.getDate("valid_from").toLocalDate(),
                rs.getDate("valid_to").toLocalDate(),
                lista(rs.getString("channels"), id, false),
                lista(rs.getString("store_formats"), id, true),
                lista(rs.getString("regions"), id, true),
                lista(rs.getString("conditions"), id, false),
                rs.getString("legal_text"),
                lista(rs.getString("quality_flags"), id, false),
                List.of());
    }

    private static Promo conTargets(Promo p, List<Target> targets) {
        return new Promo(p.id(), p.retailer(), p.title(), p.discountKind(), p.discountPercent(), p.installments(),
                p.discountAmount(), p.discountText(), p.capAmount(), p.capPeriod(), p.daysMask(), p.validFrom(),
                p.validTo(), p.channels(), p.storeFormats(), p.regions(), p.conditions(), p.legalText(),
                p.qualityFlags(), List.copyOf(targets));
    }

    /**
     * Una columna JSON con un array de strings. Un JSON roto no tira la consulta
     * entera: se loguea y queda vacío (o null, en las columnas que admiten null).
     */
    private List<String> lista(String json, long id, boolean nullable) {
        if (json == null || json.isBlank() || json.trim().equals("null")) {
            return nullable ? null : List.of();
        }
        try {
            List<String> valores = objectMapper.readValue(json, LISTA);
            return valores == null ? (nullable ? null : List.of())
                    : valores.stream().filter(v -> v != null && !v.isBlank()).toList();
        } catch (Exception e) {
            log.warn("bank_promotions.id={}: JSON inválido ({})", id, e.getMessage());
            return nullable ? null : List.of();
        }
    }
}
