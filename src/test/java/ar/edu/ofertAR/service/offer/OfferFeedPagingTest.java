package ar.edu.ofertAR.service.offer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paging the offers feed. The first page gives part of itself to campaign
 * promotions, so the catalog behind it does not continue on a page boundary.
 */
class OfferFeedPagingTest {

    @Test
    @DisplayName("campaigns take at most half the first page")
    void campaignsTakeHalfTheFirstPageAtMost() {
        assertEquals(25, OfferFeedClient.campaignsOnFirstPage(52, 50));
        assertEquals(4, OfferFeedClient.campaignsOnFirstPage(4, 50));
        assertEquals(0, OfferFeedClient.campaignsOnFirstPage(0, 50));
        // A tiny page still shows one, rather than rounding campaigns away.
        assertEquals(1, OfferFeedClient.campaignsOnFirstPage(52, 1));
    }

    @Test
    @DisplayName("the second page resumes where the first stopped, not at a page boundary")
    void secondPageResumesWhereTheFirstStopped() {
        // 50-row page, 25 campaign slots: the first page shows catalog rows
        // 0..24, so the second must start at 25. Paging by page number asked
        // for row 50 and rows 25..49 were reachable from nowhere.
        assertEquals(new OfferFeedClient.CatalogWindow(0, 25), OfferFeedClient.catalogWindow(1, 50, 25));
        assertEquals(new OfferFeedClient.CatalogWindow(25, 50), OfferFeedClient.catalogWindow(2, 50, 25));
        assertEquals(new OfferFeedClient.CatalogWindow(75, 50), OfferFeedClient.catalogWindow(3, 50, 25));
    }

    @Test
    @DisplayName("with no campaigns the pages are plain and contiguous")
    void withoutCampaignsThePagesAreContiguous() {
        assertEquals(new OfferFeedClient.CatalogWindow(0, 50), OfferFeedClient.catalogWindow(1, 50, 0));
        assertEquals(new OfferFeedClient.CatalogWindow(50, 50), OfferFeedClient.catalogWindow(2, 50, 0));
        assertEquals(new OfferFeedClient.CatalogWindow(100, 50), OfferFeedClient.catalogWindow(3, 50, 0));
    }

    @Test
    @DisplayName("walking every page reaches each catalog row exactly once")
    void everyRowIsReachedExactlyOnce() {
        int pageSize = 50;
        int campaignsShown = OfferFeedClient.campaignsOnFirstPage(52, pageSize);
        long catalogTotal = 628; // Makro's real catalog size
        int pages = OfferFeedClient.totalPages(catalogTotal, pageSize, campaignsShown);

        Set<Long> seen = new HashSet<>();
        for (int page = 1; page <= pages; page++) {
            OfferFeedClient.CatalogWindow w = OfferFeedClient.catalogWindow(page, pageSize, campaignsShown);
            for (long row = w.offset(); row < Math.min(w.offset() + w.limit(), catalogTotal); row++) {
                assertTrue(seen.add(row), "row " + row + " served twice, on page " + page);
            }
        }
        assertEquals(catalogTotal, seen.size(), "some rows were never served");
        assertTrue(seen.contains(25L), "the row right after the first page's cut must be reachable");
    }

    @Test
    @DisplayName("the page count covers the whole catalog and no more")
    void pageCountCoversTheCatalog() {
        assertEquals(1, OfferFeedClient.totalPages(10, 50, 25));
        assertEquals(1, OfferFeedClient.totalPages(25, 50, 25));
        assertEquals(2, OfferFeedClient.totalPages(26, 50, 25));
        assertEquals(2, OfferFeedClient.totalPages(75, 50, 25));
        assertEquals(3, OfferFeedClient.totalPages(76, 50, 25));
        assertEquals(1, OfferFeedClient.totalPages(0, 50, 0));
    }
}
