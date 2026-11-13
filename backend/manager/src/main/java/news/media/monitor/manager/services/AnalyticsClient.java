package news.media.monitor.manager.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Public chart reads of the analytics service used by the watchlist. Failures return empty lists:
 * the watchlist is still served, without quotes or latest events.
 */
@Slf4j
@Component
public class AnalyticsClient {

    public record Listing(String symbol, String exchange, String currency, String name, String companyId) {}

    public record Quote(Listing listing, long time, double last, Double previousClose, Double change, Double changePct) {}

    public record LatestEvent(String symbol, String exchange, String storyId, String companyId, String title,
                              long firstSeen, Double impactScore, Double sentiment, Integer articleCount,
                              List<String> topics) {}

    private final RestClient client;

    public AnalyticsClient(@Value("${app.analytics.base-url}") String baseUrl,
                           @Value("${app.analytics.timeout-ms:2000}") long timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public List<Quote> quotes(List<String> symbols) {
        return get("/api/charts/quotes", symbols, Quote[].class);
    }

    public List<LatestEvent> latestEvents(List<String> symbols) {
        return get("/api/charts/latest-events", symbols, LatestEvent[].class);
    }

    private <T> List<T> get(String path, List<String> symbols, Class<T[]> type) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        try {
            T[] body = client.get()
                    .uri(b -> b.path(path).queryParam("symbols", String.join(",", symbols)).build())
                    .retrieve().body(type);
            return body == null ? List.of() : List.of(body);
        } catch (RestClientException e) {
            log.warn("Analytics call {} failed: {}", path, e.getMessage());
            return List.of();
        }
    }
}
