package data.analytics.events;

import data.analytics.provider.ProviderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rebuild of the read model from the provider's paged GET /api/events, and the real
 * RabbitMQ consumer fed a provider-shaped message (JSON body + the provider's __TypeId__ header).
 */
@SpringBootTest
class EventRebuildIntegrationTests {

    @MockitoBean private ProviderClient provider;
    @Autowired private EventRebuildService rebuild;
    @Autowired private JdbcClient jdbc;
    @Autowired private RabbitTemplate rabbit;

    private final String run = "it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM events WHERE story_id LIKE ?").param(run + "%").update();
        jdbc.sql("DELETE FROM event_tombstones WHERE story_id LIKE ?").param(run + "%").update();
    }

    private EventMessage msg(int i, long version, boolean deleted) {
        return new EventMessage(run + "-" + i, "c1", "Bank One", "ONE", "XNYS", "USD", "Story " + i,
                "2026-11-10T09:00:00Z", 0.5, 1L, 0.0, List.of("earnings_results"), deleted, version);
    }

    private int stored() {
        return jdbc.sql("SELECT count(*) FROM events WHERE story_id LIKE ?").param(run + "%").query(Integer.class).single();
    }

    @Test
    void rebuildFollowsPagesAndIsIdempotent() {
        List<EventMessage> page0 = IntStream.range(0, 500).mapToObj(i -> msg(i, 10, false)).toList();
        List<EventMessage> page1 = new ArrayList<>(List.of(msg(500, 10, false), msg(501, 10, false)));
        when(provider.events(eq("2026-01-01T00:00:00Z"), eq(0), anyInt())).thenReturn(page0);
        when(provider.events(eq("2026-01-01T00:00:00Z"), eq(1), anyInt())).thenReturn(page1);

        assertThat(rebuild.rebuild("2026-01-01T00:00:00Z")).isEqualTo(502);
        assertThat(stored()).isEqualTo(502);
        verify(provider).events("2026-01-01T00:00:00Z", 1, 500);        // stopped after the short page

        assertThat(rebuild.rebuild("2026-01-01T00:00:00Z")).isZero();      // replay: nothing changes

        page1.set(0, msg(500, 20, true));                                  // removed upstream since
        assertThat(rebuild.rebuild("2026-01-01T00:00:00Z")).isEqualTo(1);
        assertThat(stored()).isEqualTo(501);
    }

    @Test
    void consumesProviderShapedMessageFromRabbitMq() throws Exception {
        String json = """
                {"storyId":"%s-mq","companyId":"c1","companyName":"Bank One","symbol":"ONE","exchange":"XNYS",
                 "currency":"USD","storyTitle":"Bank fined","firstSeen":"2026-11-10T09:00Z[UTC]","impactScore":0.62,
                 "articleCount":2,"sentiment":-0.4,"topics":["regulation_legal_enforcement"],"deleted":false,
                 "version":%d}
                """.formatted(run, System.currentTimeMillis());
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setHeader("__TypeId__", "data.provider.dto.messages.EventMessage");   // what the provider sends
        rabbit.send("", "analytics.events", new Message(json.getBytes(StandardCharsets.UTF_8), props));

        Double impact = null;
        for (int i = 0; i < 100 && impact == null; i++) {
            Thread.sleep(100);
            impact = jdbc.sql("SELECT impact_score FROM events WHERE story_id = ?").param(run + "-mq")
                    .query(Double.class).optional().orElse(null);
        }
        assertThat(impact).isEqualTo(0.62);
    }
}
