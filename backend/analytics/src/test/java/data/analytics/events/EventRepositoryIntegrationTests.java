package data.analytics.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Version guards and tombstones of the event read model, against the market database. */
@SpringBootTest
class EventRepositoryIntegrationTests {

    @Autowired private EventRepository events;
    @Autowired private JdbcClient jdbc;

    private final String story = "it-" + UUID.randomUUID();

    private EventMessage msg(long version, boolean deleted, double impact) {
        return new EventMessage(story, "c1", "Bank One", "ONE", "XNYS", "USD", "Bank fined",
                "2026-11-10T09:00Z[UTC]", impact, 2L, -0.5, List.of("regulation_legal_enforcement", "earnings_results"),
                deleted, version);
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM events WHERE story_id = ?").param(story).update();
        jdbc.sql("DELETE FROM event_tombstones WHERE story_id = ?").param(story).update();
    }

    private Double storedImpact() {
        return jdbc.sql("SELECT impact_score FROM events WHERE story_id = ?").param(story).query(Double.class).optional().orElse(null);
    }

    @Test
    void newerVersionWinsAndOlderIsIgnored() {
        assertThat(events.apply(msg(10, false, 0.5))).isTrue();
        assertThat(events.apply(msg(20, false, 0.7))).isTrue();
        assertThat(events.apply(msg(15, false, 0.1))).isFalse();      // out of order: ignored
        assertThat(events.apply(msg(20, false, 0.9))).isFalse();      // duplicate version: ignored
        assertThat(storedImpact()).isEqualTo(0.7);
    }

    @Test
    void deleteLeavesTombstoneSoLateOlderUpsertCannotReviveTheEvent() {
        events.apply(msg(10, false, 0.5));
        assertThat(events.apply(msg(30, true, 0))).isTrue();
        assertThat(storedImpact()).isNull();
        assertThat(events.apply(msg(20, false, 0.6))).isFalse();     // arrives late, older than the delete
        assertThat(storedImpact()).isNull();
        assertThat(events.apply(msg(40, false, 0.8))).isTrue();      // a genuinely newer upsert brings it back
        assertThat(storedImpact()).isEqualTo(0.8);
    }

    @Test
    void storesTopicsArrayAndNeo4jStyleTimestamp() {
        events.apply(msg(10, false, 0.5));
        String row = jdbc.sql("SELECT array_to_string(topics, '|') || ' ' || to_char(first_seen AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') FROM events WHERE story_id = ?")
                .param(story).query(String.class).single();
        assertThat(row).isEqualTo("regulation_legal_enforcement|earnings_results 2026-11-10 09:00");
    }
}
