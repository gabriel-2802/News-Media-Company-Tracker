package news.media.monitor.manager.services;

import lombok.extern.slf4j.Slf4j;
import news.media.monitor.manager.dto.requests.WatchlistRequest;
import news.media.monitor.manager.dto.responses.WatchlistItemResponse;
import news.media.monitor.manager.exceptions.exceptions.ResourceNotFoundException;
import news.media.monitor.manager.exceptions.exceptions.WatchlistLimitException;
import news.media.monitor.manager.models.Subscription;
import news.media.monitor.manager.models.SubscriptionType;
import news.media.monitor.manager.repositories.SubscriptionRepository;
import news.media.monitor.manager.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ticker watchlist: TICKER subscriptions with alert settings (minimum impact, topic filter).
 * Symbols are validated against the provider's company universe; quotes and latest events
 * come from the analytics service when it is reachable.
 */
@Slf4j
@Service
public class WatchlistService {

    public static final int MAX_TICKERS = 50;

    private final SubscriptionRepository subscriptions;
    private final UserRepository users;
    private final CompanyDirectory companies;
    private final AnalyticsClient analytics;
    private final double defaultMinImpact;

    public WatchlistService(SubscriptionRepository subscriptions, UserRepository users, CompanyDirectory companies,
                            AnalyticsClient analytics,
                            @Value("${app.alerts.default-min-impact:0.5}") double defaultMinImpact) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.companies = companies;
        this.analytics = analytics;
        this.defaultMinImpact = defaultMinImpact;
    }

    public double defaultMinImpact() {
        return defaultMinImpact;
    }

    @Transactional(readOnly = true)
    public List<WatchlistItemResponse> list(String email) {
        List<Subscription> watched = subscriptions.findByUserIdAndTypeOrderByCreatedAtAsc(userId(email), SubscriptionType.TICKER);
        List<String> symbols = watched.stream().map(Subscription::getTargetId).toList();
        Map<String, AnalyticsClient.Quote> quotes = analytics.quotes(symbols).stream()
                .collect(Collectors.toMap(q -> q.listing().symbol(), Function.identity(), (a, b) -> a));
        Map<String, AnalyticsClient.LatestEvent> events = analytics.latestEvents(symbols).stream()
                .collect(Collectors.toMap(AnalyticsClient.LatestEvent::symbol, Function.identity(), (a, b) -> a));
        return watched.stream().map(s -> item(s, quotes.get(s.getTargetId()), events.get(s.getTargetId()))).toList();
    }

    /**
     * Adds a ticker, or updates its settings when already watched (null fields keep the current value).
     * Returns the item and whether it was created.
     */
    @Transactional
    public Result put(String email, String rawSymbol, WatchlistRequest request) {
        Long userId = userId(email);
        String symbol = CompanyDirectory.normalize(rawSymbol);
        Subscription s = subscriptions.findByUserIdAndTypeAndTargetId(userId, SubscriptionType.TICKER, symbol).orElse(null);
        boolean created = s == null;
        if (created) {
            companies.find(symbol).orElseThrow(() -> new ResourceNotFoundException("Unknown ticker symbol: " + symbol));
            if (subscriptions.countByUserIdAndType(userId, SubscriptionType.TICKER) >= MAX_TICKERS) {
                throw new WatchlistLimitException(MAX_TICKERS);
            }
            s = new Subscription();
            s.setUser(users.getReferenceById(userId));
            s.setType(SubscriptionType.TICKER);
            s.setTargetId(symbol);
            s.setMinImpact(defaultMinImpact);
        }
        if (request != null && request.minImpact() != null) {
            s.setMinImpact(request.minImpact());
        }
        if (request != null && request.topics() != null) {
            s.setTopics(request.topics().stream().distinct().toList());
        }
        s = subscriptions.saveAndFlush(s);
        log.info("User {} {} ticker {} (minImpact {}, topics {})", userId, created ? "watches" : "updated",
                symbol, s.getMinImpact(), s.getTopics());
        return new Result(item(s, null, null), created);
    }

    @Transactional
    public void remove(String email, String rawSymbol) {
        Long userId = userId(email);
        String symbol = CompanyDirectory.normalize(rawSymbol);
        Subscription s = subscriptions.findByUserIdAndTypeAndTargetId(userId, SubscriptionType.TICKER, symbol)
                .orElseThrow(() -> new ResourceNotFoundException("Ticker not on the watchlist: " + symbol));
        subscriptions.delete(s);
        log.info("User {} stopped watching {}", userId, symbol);
    }

    public record Result(WatchlistItemResponse item, boolean created) {}

    private WatchlistItemResponse item(Subscription s, AnalyticsClient.Quote q, AnalyticsClient.LatestEvent e) {
        String name = q != null ? q.listing().name()
                : companies.find(s.getTargetId()).map(CompanyDirectory.Ticker::name).orElse(null);
        return new WatchlistItemResponse(s.getId(), s.getTargetId(), name,
                s.getMinImpact() == null ? defaultMinImpact : s.getMinImpact(), s.getTopics(), s.getCreatedAt(),
                q == null ? null : new WatchlistItemResponse.Quote(q.listing().exchange(), q.listing().currency(),
                        q.time(), q.last(), q.previousClose(), q.change(), q.changePct()),
                e == null ? null : new WatchlistItemResponse.LatestEvent(e.storyId(), e.companyId(), e.title(),
                        e.firstSeen(), e.impactScore(), e.sentiment(), e.articleCount(), e.topics()));
    }

    private Long userId(String email) {
        return users.findIdByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
    }
}
