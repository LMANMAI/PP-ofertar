package ar.edu.ofertAR.repository;

import java.time.LocalDateTime;

/**
 * Un código de barras tal como quedó en los ítems de ticket de un usuario y la
 * fecha del ticket más reciente que lo trae. El código va sin normalizar: un
 * mismo producto puede aparecer en varias filas con formatos distintos, y
 * juntarlas es trabajo de quien lo usa (con {@code ProductKeys.sameEan}/{@code eanKey}).
 */
public record BarcodeLastPurchase(String barcode, LocalDateTime lastPurchasedAt) {}
