package ar.edu.ofertAR.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which chains a page of "ofertas en tus super" is built from. The filter has
 * to narrow the query, not the page that came back: with 2580 Carrefour offers
 * against 628 Makro ones, filtering afterwards left three Makro rows on screen.
 */
class OfferControllerChainsTest {

    private static final List<String> FAVOURITES = List.of("carrefour", "makro");

    @Test
    @DisplayName("no filter means the user's favourites")
    void fallsBackToFavourites() {
        assertEquals(FAVOURITES, OfferController.resolveChains(null, FAVOURITES));
        assertEquals(FAVOURITES, OfferController.resolveChains("", FAVOURITES));
        assertEquals(FAVOURITES, OfferController.resolveChains("  ", FAVOURITES));
    }

    @Test
    @DisplayName("picking one chain narrows the query to it")
    void narrowsToTheRequestedChain() {
        assertEquals(List.of("makro"), OfferController.resolveChains("makro", FAVOURITES));
    }

    @Test
    @DisplayName("several chains are taken in order, ignoring blanks and repeats")
    void parsesSeveralChains() {
        assertEquals(List.of("makro", "carrefour"),
                OfferController.resolveChains(" makro , carrefour ,, makro ", FAVOURITES));
    }

    @Test
    @DisplayName("a request cannot widen past the chains the user follows")
    void cannotWidenBeyondFavourites() {
        assertEquals(List.of("makro"), OfferController.resolveChains("makro,dia,coto", FAVOURITES));
    }

    @Test
    @DisplayName("asking only for chains the user dropped is a stale filter, not an empty screen")
    void staleFilterFallsBackInsteadOfBlanking() {
        assertEquals(FAVOURITES, OfferController.resolveChains("dia,coto", FAVOURITES));
    }

    @Test
    @DisplayName("with no favourites set there is nothing to narrow against")
    void noFavouritesLetsTheRequestStand() {
        assertEquals(List.of("makro"), OfferController.resolveChains("makro", List.of()));
        assertEquals(List.of("makro"), OfferController.resolveChains("makro", null));
    }

    @Test
    @DisplayName("a comma-separated parameter becomes a trimmed, de-duplicated list")
    void splitsCsvParameters() {
        assertEquals(List.of("Almacén", "Bebidas"),
                OfferController.splitCsv(" Almacén , Bebidas ,, Almacén "));
    }

    @Test
    @DisplayName("an absent parameter is no filter, not an empty result")
    void absentCsvIsNoFilter() {
        assertEquals(List.of(), OfferController.splitCsv(null));
        assertEquals(List.of(), OfferController.splitCsv(""));
        assertEquals(List.of(), OfferController.splitCsv(" , , "));
    }
}
