package ar.edu.ofertAR.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tickets the "AHORRO DEL MES" card is allowed to count.
 */
class SavingsServiceMonthFilterTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Test
    @DisplayName("asking for September leaves August tickets out")
    void keepsOnlyTheRequestedMonth() {
        // The user's own case: two tickets scanned on 31/08 and one on 01/09,
        // all three showing on the home card on 02/09.
        LocalDateTime firstAugust = LocalDateTime.of(2026, 8, 31, 15, 52);
        LocalDateTime secondAugust = LocalDateTime.of(2026, 8, 31, 21, 30);
        LocalDateTime september = LocalDateTime.of(2026, 9, 1, 15, 52);

        List<LocalDateTime> kept = List.of(firstAugust, secondAugust, september).stream()
                .filter(t -> SavingsService.withinMonths(t, SEPTEMBER, SEPTEMBER))
                .toList();

        assertEquals(List.of(september), kept);
    }

    @Test
    @DisplayName("a ticket late on the last night of the month stays in that month")
    void keepsALateNightTicketInItsOwnMonth() {
        // 21:30 on 31/08 in Buenos Aires is already 00:30 on 01/09 in UTC.
        // With the JVM left on UTC this ticket was stamped into September;
        // the application now fixes the zone, and this pins the expectation.
        LocalDateTime lastNight = LocalDateTime.of(2026, 8, 31, 21, 30);

        assertFalse(SavingsService.withinMonths(lastNight, SEPTEMBER, SEPTEMBER));
        assertTrue(SavingsService.withinMonths(lastNight, YearMonth.of(2026, 8), YearMonth.of(2026, 8)));
    }

    @Test
    @DisplayName("no range means the whole history, which is why the caller must pass one")
    void openRangeKeepsEverything() {
        LocalDateTime august = LocalDateTime.of(2026, 8, 31, 15, 52);

        assertTrue(SavingsService.withinMonths(august, null, null));
    }

    @Test
    @DisplayName("the bounds are inclusive on both ends")
    void boundsAreInclusive() {
        LocalDateTime august = LocalDateTime.of(2026, 8, 10, 12, 0);
        LocalDateTime september = LocalDateTime.of(2026, 9, 10, 12, 0);
        YearMonth august2026 = YearMonth.of(2026, 8);

        assertTrue(SavingsService.withinMonths(august, august2026, SEPTEMBER));
        assertTrue(SavingsService.withinMonths(september, august2026, SEPTEMBER));
        assertFalse(SavingsService.withinMonths(LocalDateTime.of(2026, 7, 31, 23, 59), august2026, SEPTEMBER));
        assertFalse(SavingsService.withinMonths(LocalDateTime.of(2026, 10, 1, 0, 0), august2026, SEPTEMBER));
    }
}
