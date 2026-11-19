package data.analytics.charts;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chart endpoints through HTTP against the market database, on a private test
 * exchange XTST with its own benchmark. Fixture:
 *   ITBANK prices for every weekday 2026-01-01 .. 2026-06-30 (close rises by 1 per day from 100),
 *   ITBENCH benchmark (random walk), and three events:
 *     e-neg  2026-06-06 (Saturday)  sentiment -0.8, impact 0.9, 3 articles, topic regulation
 *     e-pos  2026-06-10 (Wednesday) sentiment +0.6, impact 0.5, 1 article,  topic earnings
 *     e-neu  2026-06-11 (Thursday)  sentiment  0.0, impact 0.2, 1 article,  topics earnings + m&a
 */
@SpringBootTest(properties = "analytics.benchmarks.XTST=ITBENCH@XTST")
@AutoConfigureMockMvc
class ChartApiIntegrationTests {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;

    private int tradingDays;

    private static long epoch(final LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("INSERT INTO companies (symbol, exchange, company_id, name, kind, currency, is_primary) VALUES ('ITBANK', 'XTST', 'it-co', 'Test Bank', 'BANK', 'USD', true)").update();
        final Random rnd = new Random(3);
        double close = 100, bench = 100;
        tradingDays = 0;
        for (LocalDate d = START; !d.isAfter(END); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            bench *= Math.exp(rnd.nextGaussian() * 0.01);
            jdbc.sql("INSERT INTO prices_daily (symbol, exchange, day, open, high, low, close, volume, source) VALUES ('ITBANK', 'XTST', ?, ?, ?, ?, ?, 1000, 'test'), ('ITBENCH', 'XTST', ?, NULL, NULL, NULL, ?, NULL, 'test')")
                    .params(d, close - 0.5, close + 1, close - 1, close, d, bench).update();
            close += 1;
            tradingDays++;
        }
        event("e-neg", "2026-06-06T15:00:00Z", -0.8, 0.9, 3, "{regulation_legal_enforcement}");
        event("e-pos", "2026-06-10T12:00:00Z", 0.6, 0.5, 1, "{earnings_results}");
        event("e-neu", "2026-06-11T12:00:00Z", 0.0, 0.2, 1, "{earnings_results,m_and_a_investments}");
    }

    private void event(String id, String firstSeen, double sentiment, double impact, int articles, String topics) {
        jdbc.sql("""
                INSERT INTO events (story_id, company_id, company_name, symbol, exchange, currency, story_title, first_seen,
                                    impact_score, article_count, sentiment, topics, version)
                VALUES (?, 'it-co', 'Test Bank', 'ITBANK', 'XTST', 'USD', ?, CAST(? AS timestamptz), ?, ?, ?, CAST(? AS text[]), 1)
                """).params(id, "Story " + id, firstSeen, impact, articles, sentiment, topics).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM events WHERE exchange = 'XTST'").update();
        jdbc.sql("DELETE FROM prices_daily WHERE exchange = 'XTST'").update();
        jdbc.sql("DELETE FROM companies WHERE exchange = 'XTST'").update();
    }

    private String body(final String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void priceSeriesAndDownsampling() throws Exception {
        String full = body("/api/charts/companies/ITBANK/price?from=2026-01-01&to=2026-06-30");
        assertThat(JsonPath.<Integer>read(full, "$.bars.length()")).isEqualTo(tradingDays);
        assertThat(JsonPath.<Number>read(full, "$.bars[0].time").longValue()).isEqualTo(epoch(LocalDate.of(2026, 1, 1)));
        assertThat(JsonPath.<String>read(full, "$.listing.name")).isEqualTo("Test Bank");
        assertThat(JsonPath.<Boolean>read(full, "$.downsampled")).isFalse();

        String small = body("/api/charts/companies/ITBANK/price?from=2026-01-01&to=2026-06-30&maxPoints=50");
        int n = JsonPath.<Integer>read(small, "$.bars.length()");
        assertThat(n).isLessThanOrEqualTo(50);
        assertThat(JsonPath.<Boolean>read(small, "$.downsampled")).isTrue();
        // bucket keeps first open, last close, max high, min low
        assertThat(JsonPath.<Double>read(small, "$.bars[0].open")).isEqualTo(99.5);
        int size = (int) Math.ceil(tradingDays / 50.0);
        assertThat(JsonPath.<Double>read(small, "$.bars[0].close")).isEqualTo(100.0 + size - 1);
        assertThat(JsonPath.<Double>read(small, "$.bars[0].high")).isEqualTo(100.0 + size);
        assertThat(JsonPath.<Double>read(small, "$.bars[0].low")).isEqualTo(99.0);
    }

    @Test
    void markersSnapToTradingDaysAndCarryStyle() throws Exception {
        String json = body("/api/charts/companies/ITBANK/events?from=2026-06-01&to=2026-06-30");
        assertThat(JsonPath.<Integer>read(json, "$.markers.length()")).isEqualTo(3);
        // Saturday event -> Monday 2026-06-08
        assertThat(JsonPath.<Number>read(json, "$.markers[0].time").longValue()).isEqualTo(epoch(LocalDate.of(2026, 6, 8)));
        assertThat(JsonPath.<String>read(json, "$.markers[0].shape")).isEqualTo("arrowDown");
        assertThat(JsonPath.<String>read(json, "$.markers[0].position")).isEqualTo("aboveBar");
        assertThat(JsonPath.<String>read(json, "$.markers[0].severity")).isEqualTo("high");
        assertThat(JsonPath.<String>read(json, "$.markers[1].shape")).isEqualTo("arrowUp");
        assertThat(JsonPath.<String>read(json, "$.markers[2].shape")).isEqualTo("circle");

        String filtered = body("/api/charts/companies/ITBANK/events?from=2026-06-01&to=2026-06-30&topics=m_and_a_investments");
        assertThat(JsonPath.<List<String>>read(filtered, "$.markers[*].storyId")).containsExactly("e-neu");
        String impactful = body("/api/charts/companies/ITBANK/events?from=2026-06-01&to=2026-06-30&minImpact=0.5");
        assertThat(JsonPath.<List<String>>read(impactful, "$.markers[*].storyId")).containsExactly("e-neg", "e-pos");
    }

    @Test
    void overlayMarkersLandOnBarTimesEvenWhenDownsampled() throws Exception {
        String json = body("/api/charts/companies/ITBANK/overlay?from=2026-01-01&to=2026-06-30&maxPoints=50");
        List<Number> barTimes = JsonPath.read(json, "$.bars[*].time");
        List<Number> markerTimes = JsonPath.read(json, "$.markers[*].time");
        assertThat(markerTimes).hasSize(3);
        assertThat(barTimes.stream().map(Number::longValue).toList())
                .containsAll(markerTimes.stream().map(Number::longValue).toList());
    }

    @Test
    void weeklySentimentIsArticleWeighted() throws Exception {
        String json = body("/api/charts/companies/ITBANK/sentiment?from=2026-06-01&to=2026-06-30&bucket=week");
        // ISO weeks: e-neg (Sat 6 Jun) in week of Mon 1 Jun; e-pos and e-neu in week of Mon 8 Jun.
        assertThat(JsonPath.<Number>read(json, "$[0].time").longValue()).isEqualTo(epoch(LocalDate.of(2026, 6, 1)));
        assertThat(JsonPath.<Double>read(json, "$[0].avgSentiment")).isEqualTo(-0.8);
        assertThat(JsonPath.<Integer>read(json, "$[0].articles")).isEqualTo(3);
        assertThat(JsonPath.<Double>read(json, "$[1].avgSentiment")).isCloseTo((0.6 + 0.0) / 2, within(1e-9));
        assertThat(JsonPath.<Integer>read(json, "$[1].events")).isEqualTo(2);

        String volume = body("/api/charts/companies/ITBANK/news-volume?from=2026-06-01&to=2026-06-30&bucket=day");
        assertThat(JsonPath.<Integer>read(volume, "$.length()")).isEqualTo(3);
    }

    @Test
    void topicVolumeCountsEachTopicOfAnEvent() throws Exception {
        String json = body("/api/charts/topics/volume?from=2026-06-08&to=2026-06-14&bucket=week&topics=earnings_results,m_and_a_investments");
        assertThat(JsonPath.<Integer>read(json, "$[0].topics.earnings_results")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$[0].topics.m_and_a_investments")).isEqualTo(1);
    }

    @Test
    void impactDetailHasConsistentPath() throws Exception {
        String json = body("/api/charts/events/e-pos/it-co/impact");
        List<Integer> offsets = JsonPath.read(json, "$.path[*].offset");
        assertThat(offsets).containsExactly(-5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5);
        List<Double> ars = JsonPath.<List<Number>>read(json, "$.path[*].ar").stream().map(Number::doubleValue).toList();
        List<Double> cars = JsonPath.<List<Number>>read(json, "$.path[*].car").stream().map(Number::doubleValue).toList();
        assertThat(cars.getLast()).isCloseTo(ars.stream().mapToDouble(Double::doubleValue).sum(), within(1e-12));
        assertThat(ars.get(5)).isCloseTo(JsonPath.<Number>read(json, "$.ar0").doubleValue(), within(1e-12));
        assertThat(JsonPath.<Number>read(json, "$.car5").doubleValue()).isCloseTo(cars.getLast(), within(1e-12));
        assertThat(JsonPath.<String>read(json, "$.benchmark")).isEqualTo("ITBENCH");
    }

    @Test
    void quotesAndHeatmapRow() throws Exception {
        String q = body("/api/charts/quotes?symbols=ITBANK,NOSUCHSYMBOL");
        assertThat(JsonPath.<Integer>read(q, "$.length()")).isEqualTo(1);          // unknown symbol left out
        double last = 100.0 + tradingDays - 1;
        assertThat(JsonPath.<Double>read(q, "$[0].last")).isEqualTo(last);
        assertThat(JsonPath.<Double>read(q, "$[0].change")).isEqualTo(1.0);
        assertThat(JsonPath.<Double>read(q, "$[0].changePct")).isCloseTo(100 * (last / (last - 1) - 1), within(1e-3));

        // 30-day window ending 2026-06-30: base close is the last bar on or before 2026-05-31 (Friday 2026-05-29).
        String h = body("/api/charts/sector/heatmap?window=30d&to=2026-06-30");
        List<Object> rows = JsonPath.read(h, "$.rows[?(@.listing.symbol == 'ITBANK')]");
        assertThat(rows).hasSize(1);
        String row = "$.rows[?(@.listing.symbol == 'ITBANK')]";
        int baseIndex = 0;
        for (LocalDate d = START; !d.isAfter(LocalDate.of(2026, 5, 29)); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) baseIndex++;
        }
        double base = 100.0 + baseIndex - 1;
        double expected = 100 * (last / base - 1);
        assertThat(JsonPath.<List<Number>>read(h, row + ".returnPct").getFirst().doubleValue()).isCloseTo(expected, within(1e-3));
        assertThat(JsonPath.<List<Integer>>read(h, row + ".events").getFirst()).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(h, row + ".topEventTitle").getFirst()).isEqualTo("Story e-neg");   // highest impact
        assertThat(JsonPath.<List<Number>>read(h, row + ".avgSentiment").getFirst().doubleValue())
                .isCloseTo((-0.8 * 3 + 0.6 * 1 + 0.0 * 1) / 5, within(1e-4));    // article-weighted
    }

    @Test
    void symbolsAreCaseInsensitive() throws Exception {
        String lower = body("/api/charts/companies/itbank/price?from=2026-06-01&to=2026-06-30&exchange=xtst");
        String upper = body("/api/charts/companies/ITBANK/price?from=2026-06-01&to=2026-06-30");
        assertThat(JsonPath.<String>read(lower, "$.listing.symbol")).isEqualTo("ITBANK");
        assertThat(JsonPath.<List<Object>>read(lower, "$.bars")).hasSameSizeAs(JsonPath.<List<Object>>read(upper, "$.bars"));
        assertThat(JsonPath.<List<Object>>read(body("/api/charts/quotes?symbols=itbank"), "$")).hasSize(1);
    }

    @Test
    void latestEventPerSymbol() throws Exception {
        String json = body("/api/charts/latest-events?symbols=itbank,NOSUCHSYMBOL");
        assertThat(JsonPath.<Integer>read(json, "$.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(json, "$[0].symbol")).isEqualTo("ITBANK");
        assertThat(JsonPath.<String>read(json, "$[0].storyId")).isEqualTo("e-neu");          // newest first_seen
        assertThat(JsonPath.<Number>read(json, "$[0].firstSeen").longValue())
                .isEqualTo(java.time.Instant.parse("2026-06-11T12:00:00Z").getEpochSecond());
        assertThat(JsonPath.<List<String>>read(json, "$[0].topics")).containsExactly("earnings_results", "m_and_a_investments");
    }

    @Test
    void errorsAndCaching() throws Exception {
        mvc.perform(get("/api/charts/companies/NOSUCHSYMBOL/price")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NOSUCHSYMBOL")));
        mvc.perform(get("/api/charts/companies/ITBANK/sentiment?bucket=month")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/charts/companies/ITBANK/price?from=2026-06-30&to=2026-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/charts/companies/ITBANK/price?maxPoints=10")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/charts/companies/ITBANK/price?from=not-a-date")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/charts/sector/heatmap?window=2d")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/charts/events/nope/nope/impact")).andExpect(status().isNotFound());

        MvcResult first = mvc.perform(get("/api/charts/companies/ITBANK/price?from=2026-01-01&to=2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=60")))
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mvc.perform(get("/api/charts/companies/ITBANK/price?from=2026-01-01&to=2026-06-30").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }
}
