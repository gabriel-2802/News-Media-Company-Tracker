package news.media.monitor.manager.services;

import lombok.extern.slf4j.Slf4j;
import news.media.monitor.manager.exceptions.exceptions.ExternalServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Listed companies known to the provider (GET /api/companies), keyed by ticker symbol. Used to
 * validate watchlist symbols and to show company names. Cached for a few minutes; when the
 * provider is down a stale copy is used, and with no copy at all the call fails with 502.
 */
@Slf4j
@Service
public class CompanyDirectory {

    private static final String PATH_COMPANIES = "/api/companies";
    private static final Duration TTL = Duration.ofMinutes(10);

    public record Ticker(String symbol, String exchange, String currency, String companyId, String name) {}

    /** Subset of the provider's CompanyDto. */
    record CompanyDto(String id, String name, List<TickerDto> tickers) {}

    record TickerDto(String symbol, String exchange, String currency, boolean primary) {}

    private final RestTemplate restTemplate;
    private final String providerUrl;

    private volatile Map<String, Ticker> bySymbol;
    private volatile Instant loadedAt = Instant.EPOCH;

    public CompanyDirectory(RestTemplate restTemplate, @Value("${app.news-provider.base-url}") String providerUrl) {
        this.restTemplate = restTemplate;
        this.providerUrl = providerUrl;
    }

    public static String normalize(String symbol) {
        return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }

    public Optional<Ticker> find(String symbol) {
        return Optional.ofNullable(symbols().get(normalize(symbol)));
    }

    private Map<String, Ticker> symbols() {
        if (bySymbol != null && loadedAt.plus(TTL).isAfter(Instant.now())) {
            return bySymbol;
        }
        synchronized (this) {
            if (bySymbol != null && loadedAt.plus(TTL).isAfter(Instant.now())) {
                return bySymbol;
            }
            try {
                CompanyDto[] companies = restTemplate.getForObject(providerUrl + PATH_COMPANIES, CompanyDto[].class);
                Map<String, Ticker> map = new HashMap<>();
                for (CompanyDto c : companies == null ? new CompanyDto[0] : companies) {
                    if (c.tickers() == null) {
                        continue;
                    }
                    for (TickerDto t : c.tickers()) {
                        // a symbol listed by several companies/exchanges: keep the primary listing
                        Ticker ticker = new Ticker(normalize(t.symbol()), t.exchange(), t.currency(), c.id(), c.name());
                        map.merge(ticker.symbol(), ticker, (old, neu) -> t.primary() ? neu : old);
                    }
                }
                bySymbol = Map.copyOf(map);
                loadedAt = Instant.now();
                log.info("Loaded {} ticker symbols from the provider", map.size());
            } catch (RestClientException e) {
                if (bySymbol == null) {
                    throw new ExternalServiceException("News provider service is unavailable", e);
                }
                log.warn("Company refresh failed, using the cached list: {}", e.getMessage());
                loadedAt = Instant.now().minus(TTL).plusSeconds(60);      // retry in a minute
            }
            return bySymbol;
        }
    }
}
