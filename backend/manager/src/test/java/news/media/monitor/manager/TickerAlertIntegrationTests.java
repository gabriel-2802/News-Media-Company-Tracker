package news.media.monitor.manager;

import news.media.monitor.manager.dto.messages.EventMessage;
import news.media.monitor.manager.dto.requests.WatchlistRequest;
import news.media.monitor.manager.models.Notification;
import news.media.monitor.manager.models.NotificationType;
import news.media.monitor.manager.repositories.NotificationRepository;
import news.media.monitor.manager.services.AnalyticsClient;
import news.media.monitor.manager.services.CompanyDirectory;
import news.media.monitor.manager.services.TickerAlertService;
import news.media.monitor.manager.services.WatchlistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * event.upserted -> watchlist alerts, against the live users schema. Listeners are not started
 * (rabbitmq.listeners-auto-startup=false), so a running broker does not interfere; messages are handed to the service.
 */
@SpringBootTest(properties = "rabbitmq.listeners-auto-startup=false")
class TickerAlertIntegrationTests {

    @MockitoBean private CompanyDirectory companies;
    @MockitoBean private AnalyticsClient analytics;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WatchlistService watchlist;
    @Autowired private TickerAlertService alerts;
    @Autowired private NotificationRepository notifications;

    private final String story = "it-story-" + UUID.randomUUID();
    private String alice;
    private String bob;
    private long aliceId;
    private long bobId;

    @BeforeEach
    void seed() {
        when(companies.find(anyString())).thenAnswer(inv -> Optional.of(
                new CompanyDirectory.Ticker(CompanyDirectory.normalize(inv.getArgument(0)), "XNYS", "USD", "co-1", "Test Bank")));
        alice = TestUsers.email();
        bob = TestUsers.email();
        aliceId = TestUsers.create(jdbc, alice);
        bobId = TestUsers.create(jdbc, bob);
    }

    @AfterEach
    void cleanUp() {
        TestUsers.deleteAll(jdbc);
    }

    private EventMessage event(double impact, List<String> topics, String firstSeen, boolean deleted) {
        return new EventMessage(story, "co-1", "Test Bank", "ITX", "XNYS", "USD", "Test Bank fined over AML controls",
                firstSeen, impact, 2, -0.6, topics, deleted, System.currentTimeMillis());
    }

    private EventMessage event(double impact, List<String> topics) {
        return event(impact, topics, Instant.now().minus(1, ChronoUnit.HOURS).toString(), false);
    }

    private List<Notification> alertsOf(long userId) {
        return notifications.findByUserId(userId, Pageable.unpaged()).stream()
                .filter(n -> n.getType() == NotificationType.TICKER_ALERT).toList();
    }

    @Test
    void alertsOnceWhenTheImpactCrossesTheThreshold() {
        watchlist.put(alice, "itx", new WatchlistRequest(0.6, null));

        assertThat(alerts.handle(event(0.4, List.of("regulation_legal_enforcement")))).isZero();      // below 0.6
        assertThat(alerts.handle(event(0.7, List.of("regulation_legal_enforcement")))).isEqualTo(1);  // crosses
        assertThat(alerts.handle(event(0.9, List.of("regulation_legal_enforcement")))).isZero();      // same event again

        assertThat(alertsOf(aliceId)).singleElement().satisfies(n -> {
            assertThat(n.getSymbol()).isEqualTo("ITX");
            assertThat(n.getStoryId()).isEqualTo(story);
            assertThat(n.getCompanyId()).isEqualTo("co-1");
            assertThat(n.getImpactScore()).isEqualTo(0.7);
            assertThat(n.getMessage()).isEqualTo(
                    "ITX: Test Bank fined over AML controls (impact 0.70, negative, regulation_legal_enforcement)");
            assertThat(n.isSeen()).isFalse();
        });
    }

    @Test
    void eachWatcherUsesOwnThresholdAndTopicFilter() {
        watchlist.put(alice, "ITX", null);                                                      // default 0.5, any topic
        watchlist.put(bob, "ITX", new WatchlistRequest(0.3, List.of("earnings_results")));

        assertThat(alerts.handle(event(0.55, List.of("regulation_legal_enforcement")))).isEqualTo(1);
        assertThat(alertsOf(aliceId)).hasSize(1);
        assertThat(alertsOf(bobId)).isEmpty();                                                   // topic not in filter

        String other = "it-story-" + UUID.randomUUID();
        EventMessage earnings = new EventMessage(other, "co-1", "Test Bank", "itx", "XNYS", "USD", "Q3 beat",
                Instant.now().toString(), 0.35, 1, 0.5, List.of("earnings_results", "analyst_ratings"), false, 1);
        assertThat(alerts.handle(earnings)).isEqualTo(1);                                        // bob only (0.35 >= 0.3)
        assertThat(alertsOf(bobId)).singleElement().satisfies(n ->
                assertThat(n.getMessage()).isEqualTo("ITX: Q3 beat (impact 0.35, positive, earnings_results, analyst_ratings)"));
        assertThat(alertsOf(aliceId)).hasSize(1);
    }

    @Test
    void deletedOldUnlistedAndUnwatchedEventsDoNotAlert() {
        watchlist.put(alice, "ITX", new WatchlistRequest(0.0, null));

        assertThat(alerts.handle(event(0.9, List.of(), Instant.now().toString(), true))).isZero();                    // deleted
        assertThat(alerts.handle(event(0.9, List.of(), Instant.now().minus(4, ChronoUnit.DAYS).toString(), false))).isZero(); // > 72h old
        assertThat(alerts.handle(new EventMessage(story, "co-2", "Unlisted", null, null, null, "t",
                Instant.now().toString(), 0.9, 1, 0.0, List.of(), false, 1))).isZero();                               // no symbol
        assertThat(alerts.handle(new EventMessage(story, "co-3", "Other", "OTHER", "XNYS", "USD", "t",
                Instant.now().toString(), 0.9, 1, 0.0, List.of(), false, 1))).isZero();                               // nobody watches
        assertThat(alertsOf(aliceId)).isEmpty();

        // Neo4j-style timestamps (zone id suffix, local time) are understood
        assertThat(alerts.handle(event(0.9, List.of(), Instant.now().toString().replace("Z", "Z[UTC]"), false))).isEqualTo(1);
    }

    @Test
    void removingTheTickerStopsAlertsAndDeletingTheUserCascades() {
        watchlist.put(alice, "ITX", null);
        watchlist.remove(alice, "itx");
        assertThat(alerts.handle(event(0.9, List.of()))).isZero();

        watchlist.put(alice, "ITX", null);
        assertThat(alerts.handle(event(0.9, List.of()))).isEqualTo(1);
        jdbc.update("DELETE FROM users.users WHERE id = ?", aliceId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.ticker_alerts WHERE user_id = ?", Integer.class, aliceId)).isZero();
    }
}
