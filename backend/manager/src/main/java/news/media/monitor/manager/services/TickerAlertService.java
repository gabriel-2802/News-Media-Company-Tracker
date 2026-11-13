package news.media.monitor.manager.services;

import lombok.extern.slf4j.Slf4j;
import news.media.monitor.manager.dto.messages.EventMessage;
import news.media.monitor.manager.models.Notification;
import news.media.monitor.manager.models.NotificationType;
import news.media.monitor.manager.models.Subscription;
import news.media.monitor.manager.models.SubscriptionType;
import news.media.monitor.manager.repositories.NotificationRepository;
import news.media.monitor.manager.repositories.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns event.upserted messages into watchlist alerts. A user watching the event's symbol gets
 * one TICKER_ALERT notification per event (story + company) once the event's impact score reaches
 * the user's threshold and, if the user set a topic filter, the event has one of those topics.
 * Events are re-published as articles join a story, so an event that starts below the threshold
 * alerts when it crosses it. Deleted events and events first seen too long ago (backfills,
 * re-enrichment) do not alert.
 */
@Slf4j
@Service
public class TickerAlertService {

    private static final int MAX_TITLE = 300;

    private final SubscriptionRepository subscriptions;
    private final NotificationRepository notifications;
    private final JdbcTemplate jdbc;
    private final double defaultMinImpact;
    private final Duration maxEventAge;
    private final Clock clock;

    public TickerAlertService(SubscriptionRepository subscriptions, NotificationRepository notifications,
                              JdbcTemplate jdbc,
                              @Value("${app.alerts.default-min-impact:0.5}") double defaultMinImpact,
                              @Value("${app.alerts.max-event-age-hours:72}") long maxEventAgeHours) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.defaultMinImpact = defaultMinImpact;
        this.maxEventAge = Duration.ofHours(maxEventAgeHours);
        this.clock = Clock.systemUTC();
    }

    /** Returns the number of alerts created. */
    @Transactional
    public int handle(EventMessage e) {
        if (e.deleted() || e.symbol() == null || e.impactScore() == null || e.storyId() == null || e.companyId() == null) {
            return 0;
        }
        Instant firstSeen = parse(e.firstSeen());
        if (firstSeen != null && firstSeen.isBefore(clock.instant().minus(maxEventAge))) {
            log.debug("Event {}/{} is older than {}; no alert", e.storyId(), e.companyId(), maxEventAge);
            return 0;
        }
        String symbol = CompanyDirectory.normalize(e.symbol());
        List<Notification> created = new ArrayList<>();
        for (Subscription s : subscriptions.findWatchers(SubscriptionType.TICKER, symbol)) {
            if (!matches(s, e)) {
                continue;
            }
            Long userId = s.getUser().getId();
            int inserted = jdbc.update("""
                    INSERT INTO users.ticker_alerts (user_id, story_id, company_id, symbol, impact_score)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, userId, e.storyId(), e.companyId(), symbol, e.impactScore());
            if (inserted == 0) {
                continue;                                   // already alerted for this event
            }
            Notification n = new Notification();
            n.setUser(s.getUser());
            n.setType(NotificationType.TICKER_ALERT);
            n.setMessage(message(symbol, e));
            n.setSymbol(symbol);
            n.setStoryId(e.storyId());
            n.setCompanyId(e.companyId());
            n.setImpactScore(e.impactScore());
            n.setSentiment(e.sentiment());
            created.add(n);
        }
        notifications.saveAll(created);
        if (!created.isEmpty()) {
            log.info("Created {} alert(s) for {} event {}/{} (impact {})", created.size(), symbol,
                    e.storyId(), e.companyId(), e.impactScore());
        }
        return created.size();
    }

    boolean matches(Subscription s, EventMessage e) {
        double threshold = s.getMinImpact() == null ? defaultMinImpact : s.getMinImpact();
        if (e.impactScore() < threshold) {
            return false;
        }
        List<String> filter = s.getTopics();
        return filter == null || filter.isEmpty()
                || (e.topics() != null && e.topics().stream().anyMatch(filter::contains));
    }

    static String message(String symbol, EventMessage e) {
        String title = e.storyTitle() == null ? "(untitled story)" : e.storyTitle();
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE - 1) + "…";
        }
        StringBuilder sb = new StringBuilder()
                .append(symbol).append(": ").append(title)
                .append(String.format(Locale.ROOT, " (impact %.2f", e.impactScore()));
        if (e.sentiment() != null) {
            sb.append(", ").append(e.sentiment() < -0.15 ? "negative" : e.sentiment() > 0.15 ? "positive" : "neutral");
        }
        if (e.topics() != null && !e.topics().isEmpty()) {
            sb.append(", ").append(String.join(", ", e.topics()));
        }
        return sb.append(')').toString();
    }

    /** Neo4j toString(datetime): ISO offset date-time, optionally with a [zone id]; local times are UTC. */
    static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
