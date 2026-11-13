package data.analytics.market;

import java.time.LocalDate;
import java.util.List;

/** Used when no market-data provider is configured: covers nothing, fetches nothing. */
public class NoopMarketDataProvider implements MarketDataProvider {
    @Override public String name() { return "none"; }
    @Override public boolean supports(final String exchange) { return false; }
    @Override public List<Bar> daily(final String symbol, final String exchange, final LocalDate from, final LocalDate to) {
        return List.of();
    }
}
