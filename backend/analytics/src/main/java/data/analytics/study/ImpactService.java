package data.analytics.study;

import data.analytics.config.AnalyticsProperties;
import data.analytics.config.JobLock;
import data.analytics.market.Bar;
import data.analytics.market.PriceRepository;
import data.analytics.provider.ProviderClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the event study for events on listed companies whose exchange has a
 * benchmark, stores it in event_impact and writes it back to the provider.
 * Events are revisited until the [-5, +5] window is complete.
 */
@Service
@Slf4j
public class ImpactService {

    public record Summary(int candidates, int computed, int pending, int noBenchmark) {}

    private record Pending(String storyId, String companyId, String symbol, String exchange, OffsetDateTime firstSeen) {}

    private final AnalyticsProperties props;
    private final JdbcClient jdbc;
    private final PriceRepository prices;
    private final ProviderClient provider;
    private final JobLock lock;
    private final EventStudy study;

    public ImpactService(final AnalyticsProperties props, final JdbcClient jdbc, final PriceRepository prices,
                         final ProviderClient provider, final JobLock lock) {
        this.props = props;
        this.jdbc = jdbc;
        this.prices = prices;
        this.provider = provider;
        this.lock = lock;
        final AnalyticsProperties.Study s = props.study();
        this.study = new EventStudy(s.estimationStart(), s.estimationEnd(), s.minEstimationDays());
    }

    @Scheduled(fixedDelayString = "${analytics.impact-interval-ms}", initialDelay = 120_000)
    public void scheduled() {
        run();
    }

    public Summary run() {
        if (!lock.tryAcquire("event-impact", Duration.ofMinutes(30))) {
            return new Summary(0, 0, 0, 0);
        }
        try {
            return compute();
        } finally {
            lock.release("event-impact");
        }
    }

    private Summary compute() {
        final List<Pending> pending = jdbc.sql("""
                SELECT e.story_id, e.company_id, e.symbol, e.exchange, e.first_seen
                FROM events e LEFT JOIN event_impact i ON i.story_id = e.story_id AND i.company_id = e.company_id
                WHERE e.symbol IS NOT NULL AND e.first_seen IS NOT NULL AND (i.story_id IS NULL OR NOT i.complete)
                ORDER BY e.first_seen
                """).query((rs, n) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class))).list();
        int computed = 0, waiting = 0, noBenchmark = 0;
        for (Pending p : pending) {
            final String bench = props.benchmarks().get(p.exchange());
            if (bench == null) {
                noBenchmark++;
                continue;
            }
            final var b = EventStudy.parseListing(bench);
            final LocalDate eventDay = p.firstSeen().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            final LocalDate from = eventDay.minusDays(420);       // ~250 trading days + holidays
            final LocalDate to = eventDay.plusDays(20);
            final List<Bar> stock = prices.range(p.symbol(), p.exchange(), from, to);
            final List<Bar> market = prices.range(b.getKey(), b.getValue(), from, to);
            final var result = study.run(stock, market, eventDay);
            if (result.isEmpty()) {
                waiting++;
                continue;
            }
            final EventStudy.Result r = result.get();
            jdbc.sql("""
                    INSERT INTO event_impact (story_id, company_id, symbol, exchange, event_day, benchmark, alpha, beta,
                                              ar0, car1, car5, p_value, estimation_days, complete, computed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (story_id, company_id) DO UPDATE SET
                        event_day = EXCLUDED.event_day, benchmark = EXCLUDED.benchmark, alpha = EXCLUDED.alpha,
                        beta = EXCLUDED.beta, ar0 = EXCLUDED.ar0, car1 = EXCLUDED.car1, car5 = EXCLUDED.car5,
                        p_value = EXCLUDED.p_value, estimation_days = EXCLUDED.estimation_days,
                        complete = EXCLUDED.complete, computed_at = now()
                    """).params(p.storyId(), p.companyId(), p.symbol(), p.exchange(), r.eventDay(), b.getKey(),
                    r.alpha(), r.beta(), r.ar0(), r.car1(), r.car5(), r.pValue(), r.estimationDays(), r.complete()).update();

            final Map<String, Object> body = new HashMap<>();
            body.put("benchmark", b.getKey());
            body.put("abnormalReturn", r.ar0());
            body.put("car1", r.car1());
            body.put("car5", r.car5());
            body.put("beta", r.beta());
            body.put("pValue", r.pValue());
            body.put("estimationDays", r.estimationDays());
            provider.patchImpact(p.storyId(), p.companyId(), body);
            computed++;
        }
        final Summary s = new Summary(pending.size(), computed, waiting, noBenchmark);
        log.info("Event impact: {}", s);
        return s;
    }
}
