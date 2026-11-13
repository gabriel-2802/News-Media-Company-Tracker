package data.analytics.market;

import java.time.LocalDate;
import java.util.List;

/** Source of daily bars. Implementations are chosen with analytics.market-data.provider. */
public interface MarketDataProvider {

    String name();

    /** Whether the configured plan covers listings on this exchange (ISO 10383 MIC). */
    boolean supports(String exchange);

    /** Daily bars in [from, to], oldest first. */
    List<Bar> daily(String symbol, String exchange, LocalDate from, LocalDate to);

    class MarketDataException extends RuntimeException {
        public MarketDataException(final String message) {
            super(message);
        }
    }
}
