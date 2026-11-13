package data.analytics.market;

import data.analytics.config.AnalyticsProperties;
import data.analytics.config.JobLock;
import data.analytics.provider.ProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Daily price ingest for every primary listing in the provider's company
 * universe plus the configured benchmarks, limited to exchanges the
 * market-data plan covers. Incremental: fetches from the day after the last
 * stored bar (or history-days back on first run).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PriceIngestService {

    public record Listing(String symbol, String exchange) {}

    public record Summary(int listings, int fetched, int bars, int failed, int unsupported) {}

    private final AnalyticsProperties props;
    private final MarketDataProvider market;
    private final PriceRepository prices;
    private final ProviderClient provider;
    private final JobLock lock;
    private final CompanyDirectory directory;

    @Scheduled(cron = "${analytics.ingest-cron}", zone = "UTC")
    public void scheduled() {
        ingest();
    }

    public Summary ingest() {
        if (!lock.tryAcquire("price-ingest", Duration.ofHours(3))) {
            log.info("Price ingest already running on another replica");
            return new Summary(0, 0, 0, 0, 0);
        }
        try {
            return run();
        } finally {
            lock.release("price-ingest");
        }
    }

    Set<Listing> listings() {
        final Set<Listing> all = new LinkedHashSet<>();
        props.benchmarks().values().forEach(b -> {
            final int at = b.lastIndexOf('@');
            all.add(new Listing(b.substring(0, at), b.substring(at + 1)));
        });
        final var companies = provider.companies();
        directory.refresh(companies);              // names for charts (heatmap, quotes)
        companies.forEach(c -> c.tickers().stream().filter(ProviderClient.CompanyListing.Ticker::primary)
                .forEach(t -> all.add(new Listing(t.symbol(), t.exchange()))));
        return all;
    }

    private Summary run() {
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int fetched = 0, bars = 0, failed = 0, unsupported = 0;
        final Set<Listing> listings = listings();
        for (Listing l : listings) {
            if (!market.supports(l.exchange())) {
                unsupported++;
                continue;
            }
            final LocalDate from = prices.lastDay(l.symbol(), l.exchange()).map(d -> d.plusDays(1))
                    .orElse(today.minusDays(props.marketData().historyDays()));
            if (from.isAfter(today)) {
                continue;
            }
            try {
                final var got = market.daily(l.symbol(), l.exchange(), from, today);
                bars += prices.upsert(l.symbol(), l.exchange(), market.name(), got);
                prices.recordRun(l.symbol(), l.exchange(), got.isEmpty() ? null : got.getLast().day(), null);
                fetched++;
            } catch (RuntimeException e) {
                failed++;
                prices.recordRun(l.symbol(), l.exchange(), null, e.getMessage());
                log.warn("Price fetch failed for {}@{}: {}", l.symbol(), l.exchange(), e.getMessage());
            }
            sleep(props.marketData().requestDelayMs());
        }
        final Summary s = new Summary(listings.size(), fetched, bars, failed, unsupported);
        log.info("Price ingest ({}): {}", market.name(), s);
        return s;
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
