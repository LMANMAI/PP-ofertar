package ar.edu.ofertAR.dto.response;

import java.util.List;

/**
 * GET /payment-methods/catalog: lo que el usuario puede elegir en "Mis tarjetas".
 * Los emisores salen de payment_issuers (scraper); redes, tipos y beneficios son
 * fijos en el backend. Si la tabla del scraper no se puede leer, {@code issuers}
 * viene vacío.
 */
public record PaymentCatalogResponse(
        List<PaymentIssuerView> issuers,
        List<CatalogOption> networks,
        List<CatalogOption> cardTypes,
        List<CatalogBenefit> benefits
) {

    /** Una red (visa, mastercard...) o un tipo (credit, debit...). */
    public record CatalogOption(String slug, String name) {
    }

    /** Jubilado/a, plan sueldo, Club La Nación... */
    public record CatalogBenefit(String slug, String name, String description) {
    }
}
