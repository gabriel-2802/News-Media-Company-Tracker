package data.analytics.study;

import data.analytics.market.Bar;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Synthetic market with a known answer: stock return = 0.0002 + 1.3 * market
 * + small noise, plus planted abnormal returns of +5% on day 0 and -2% on
 * day +1. The study must recover beta and the planted ARs.
 */
class EventStudyTest {

    private static final double ALPHA = 0.0002;
    private static final double BETA = 1.3;
    private static final LocalDate EVENT = LocalDate.of(2026, 6, 10);   // a Wednesday

    private final EventStudy study = new EventStudy(-250, -30, 60);

    /** Weekday bars from start, for `days` trading days; planted ARs keyed by trading-day offset from EVENT. */
    private static List<List<Bar>> market(final int daysBefore, final int daysAfter) {
        final Random rnd = new Random(42);
        final List<LocalDate> days = new ArrayList<>();
        LocalDate d = EVENT;
        while (days.size() < daysBefore) {               // walk back over weekdays
            d = d.minusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.addFirst(d);
            }
        }
        days.add(EVENT);
        d = EVENT;
        for (int i = 0; i < daysAfter; i++) {
            do {
                d = d.plusDays(1);
            } while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY);
            days.add(d);
        }
        final int t0 = daysBefore;
        final List<Bar> stock = new ArrayList<>();
        final List<Bar> bench = new ArrayList<>();
        double s = 100, m = 100;
        for (int i = 0; i < days.size(); i++) {
            if (i > 0) {
                final double rm = rnd.nextGaussian() * 0.01;
                double rs = ALPHA + BETA * rm + rnd.nextGaussian() * 0.002;
                if (i == t0) rs += 0.05;
                if (i == t0 + 1) rs -= 0.02;
                m *= Math.exp(rm);
                s *= Math.exp(rs);
            }
            stock.add(new Bar(days.get(i), null, null, null, s, null));
            bench.add(new Bar(days.get(i), null, null, null, m, null));
        }
        return List.of(stock, bench);
    }

    @Test
    void recoversBetaAndPlantedAbnormalReturns() {
        final var data = market(300, 10);
        final EventStudy.Result r = study.run(data.get(0), data.get(1), EVENT).orElseThrow();

        assertThat(r.eventDay()).isEqualTo(EVENT);
        assertThat(r.beta()).isCloseTo(BETA, within(0.03));
        assertThat(r.ar0()).isCloseTo(0.05, within(0.006));
        assertThat(r.car1()).isCloseTo(0.03, within(0.01));        // +5% - 2% (+ noise on day -1)
        assertThat(r.car5()).isCloseTo(0.03, within(0.02));
        assertThat(r.pValue()).isLessThan(0.001);
        assertThat(r.estimationDays()).isEqualTo(221);              // trading days -250 .. -30
        assertThat(r.complete()).isTrue();
    }

    @Test
    void car5PendingUntilFiveTradingDaysAfterTheEvent() {
        final var data = market(300, 2);
        final EventStudy.Result r = study.run(data.get(0), data.get(1), EVENT).orElseThrow();
        assertThat(r.car1()).isNotNull();
        assertThat(r.car5()).isNull();
        assertThat(r.complete()).isFalse();
    }

    @Test
    void weekendEventMapsToNextTradingDay() {
        final var data = market(300, 10);
        final LocalDate saturday = EVENT.minusDays(4);               // 2026-06-06
        final EventStudy.Result r = study.run(data.get(0), data.get(1), saturday).orElseThrow();
        assertThat(r.eventDay()).isEqualTo(LocalDate.of(2026, 6, 8));   // Monday
    }

    @Test
    void noResultWithoutEnoughHistoryOrBeforeTheEventTrades() {
        final var shortHistory = market(50, 10);
        assertThat(study.run(shortHistory.get(0), shortHistory.get(1), EVENT)).isEmpty();

        final var data = market(300, 10);
        assertThat(study.run(data.get(0), data.get(1), EVENT.plusDays(60))).isEmpty();
    }
}
