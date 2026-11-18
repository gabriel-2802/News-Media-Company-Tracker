package data.analytics.study;

import data.analytics.provider.ProviderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full impact job against the market database: event + synthetic prices
 * (on a private test exchange with its own benchmark) -> event study ->
 * event_impact row -> PATCH to the provider (mocked) -> no repeat PATCH
 * when nothing changed -> CAR[-5,+5] once enough days exist.
 *
 * The job processes every pending event in the shared market database. Real
 * events (US listings) get a benchmark with no prices here, so they stay
 * pending and untouched: otherwise their studies would be stored and
 * "written back" to the mock, and never reach the real provider.
 */
@SpringBootTest(properties = {
        "analytics.benchmarks.XTST=ITBENCH@XTST",
        "analytics.benchmarks.XNYS=ITNOBENCH@XNONE",
        "analytics.benchmarks.XNAS=ITNOBENCH@XNONE"})
class ImpactServiceIntegrationTests {

    private static final LocalDate EVENT = LocalDate.of(2026, 6, 10);

    @MockitoBean private ProviderClient provider;
    @Autowired private ImpactService impact;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        when(provider.patchImpact(anyString(), anyString(), any())).thenReturn(true);
        jdbc.sql("""
                INSERT INTO events (story_id, company_id, company_name, symbol, exchange, currency, story_title, first_seen, version)
                VALUES ('it-story', 'it-co', 'Test Bank', 'ITSTOCK', 'XTST', 'USD', 'Test Bank fined', '2026-06-10T13:00:00Z', 1)
                """).update();
        insertPrices(EVENT.plusDays(3));        // day 0 and +1 only: CAR[-5,+5] not available yet
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM prices_daily WHERE exchange = 'XTST'").update();
        jdbc.sql("DELETE FROM event_impact WHERE story_id = 'it-story'").update();
        jdbc.sql("DELETE FROM events WHERE story_id = 'it-story'").update();
        jdbc.sql("DELETE FROM job_locks").update();
    }

    /** Weekday bars up to `until`: stock = 0.0001 + 1.3 * market + noise, -4% planted on the event day. */
    private void insertPrices(final LocalDate until) {
        jdbc.sql("DELETE FROM prices_daily WHERE exchange = 'XTST'").update();
        final Random rnd = new Random(11);
        double s = 50, m = 100;
        for (LocalDate d = EVENT.minusDays(420); !d.isAfter(until); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            final double rm = rnd.nextGaussian() * 0.01;
            double rs = 0.0001 + 1.3 * rm + rnd.nextGaussian() * 0.002;
            if (d.equals(EVENT)) {
                rs -= 0.04;
            }
            m *= Math.exp(rm);
            s *= Math.exp(rs);
            jdbc.sql("INSERT INTO prices_daily (symbol, exchange, day, close, source) VALUES ('ITSTOCK', 'XTST', ?, ?, 'test'), ('ITBENCH', 'XTST', ?, ?, 'test')")
                    .params(d, s, d, m).update();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void computesStoresAndWritesBackThenSkipsUnchangedAndCompletesLater() {
        impact.run();

        Map<String, Object> row = jdbc.sql("SELECT beta, ar0, car5, complete, benchmark FROM event_impact WHERE story_id = 'it-story'")
                .query().singleRow();
        assertThat((Double) row.get("beta")).isCloseTo(1.3, within(0.05));
        assertThat((Double) row.get("ar0")).isCloseTo(-0.04, within(0.006));
        assertThat(row.get("car5")).isNull();
        assertThat(row.get("complete")).isEqualTo(false);
        assertThat(row.get("benchmark")).isEqualTo("ITBENCH");

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(provider, times(1)).patchImpact(eq("it-story"), eq("it-co"), body.capture());
        assertThat(body.getValue()).containsEntry("benchmark", "ITBENCH").containsKey("car1");
        assertThat((Integer) body.getValue().get("estimationDays")).isGreaterThanOrEqualTo(200);

        clearInvocations(provider);
        impact.run();                                        // no new prices: nothing to write
        verify(provider, never()).patchImpact(eq("it-story"), eq("it-co"), any());

        insertPrices(EVENT.plusDays(14));                    // five more trading days now exist
        impact.run();
        assertThat(jdbc.sql("SELECT complete FROM event_impact WHERE story_id = 'it-story'").query(Boolean.class).single()).isTrue();
        verify(provider, times(1)).patchImpact(eq("it-story"), eq("it-co"), any());
    }

    @Test
    void failedWriteBackStoresNothingAndIsRetriedNextRun() {
        when(provider.patchImpact(eq("it-story"), eq("it-co"), any())).thenThrow(new IllegalStateException("provider down"));
        ImpactService.Summary s = impact.run();
        assertThat(s.failed()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM event_impact WHERE story_id = 'it-story'").query(Long.class).single()).isZero();

        when(provider.patchImpact(eq("it-story"), eq("it-co"), any())).thenReturn(true);
        impact.run();
        verify(provider, times(2)).patchImpact(eq("it-story"), eq("it-co"), any());
        assertThat(jdbc.sql("SELECT count(*) FROM event_impact WHERE story_id = 'it-story'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void realEventsAreLeftAlone() {
        long realStudiesBefore = jdbc.sql("SELECT count(*) FROM event_impact WHERE story_id NOT LIKE 'it-%'").query(Long.class).single();
        impact.run();
        verify(provider, never()).patchImpact(org.mockito.ArgumentMatchers.argThat(id -> !id.startsWith("it-")), anyString(), any());
        assertThat(jdbc.sql("SELECT count(*) FROM event_impact WHERE story_id NOT LIKE 'it-%'").query(Long.class).single())
                .isEqualTo(realStudiesBefore);
    }

    @Test
    void eventOnExchangeWithoutBenchmarkIsSkipped() {
        jdbc.sql("UPDATE events SET exchange = 'XNOPE' WHERE story_id = 'it-story'").update();
        ImpactService.Summary s = impact.run();
        assertThat(s.noBenchmark()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM event_impact WHERE story_id = 'it-story'").query(Long.class).single()).isZero();
    }
}
