package data.analytics.events;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Version-guarded read model. An upsert applies only if its version is newer
 * than the stored row and any tombstone; a delete leaves a tombstone so an
 * older upsert arriving later cannot revive the event. Out-of-order and
 * duplicate messages are therefore harmless.
 */
@Repository
@RequiredArgsConstructor
public class EventRepository {

    public record StoredEvent(String storyId, String companyId, String symbol, String exchange,
                              OffsetDateTime firstSeen, long version) {}

    private final JdbcClient jdbc;

    /** Returns true if the message changed the read model. */
    @Transactional
    public boolean apply(final EventMessage m) {
        final Long tomb = jdbc.sql("SELECT version FROM event_tombstones WHERE story_id = ? AND company_id = ?")
                .params(m.storyId(), m.companyId()).query(Long.class).optional().orElse(null);
        if (tomb != null && tomb >= m.version()) {
            return false;
        }
        if (m.deleted()) {
            jdbc.sql("""
                    INSERT INTO event_tombstones (story_id, company_id, version) VALUES (?, ?, ?)
                    ON CONFLICT (story_id, company_id) DO UPDATE SET version = GREATEST(event_tombstones.version, EXCLUDED.version)
                    """).params(m.storyId(), m.companyId(), m.version()).update();
            jdbc.sql("DELETE FROM event_impact WHERE story_id = ? AND company_id = ?").params(m.storyId(), m.companyId()).update();
            return jdbc.sql("DELETE FROM events WHERE story_id = ? AND company_id = ? AND version < ?")
                    .params(m.storyId(), m.companyId(), m.version()).update() > 0;
        }
        return jdbc.sql("""
                INSERT INTO events (story_id, company_id, company_name, symbol, exchange, currency, story_title,
                                    first_seen, impact_score, article_count, sentiment, topics, version, updated_at)
                VALUES (:storyId, :companyId, :companyName, :symbol, :exchange, :currency, :storyTitle,
                        CAST(:firstSeen AS timestamptz), :impactScore, :articleCount, :sentiment,
                        CAST(:topics AS text[]), :version, now())
                ON CONFLICT (story_id, company_id) DO UPDATE SET
                    company_name = EXCLUDED.company_name, symbol = EXCLUDED.symbol, exchange = EXCLUDED.exchange,
                    currency = EXCLUDED.currency, story_title = EXCLUDED.story_title, first_seen = EXCLUDED.first_seen,
                    impact_score = EXCLUDED.impact_score, article_count = EXCLUDED.article_count,
                    sentiment = EXCLUDED.sentiment, topics = EXCLUDED.topics, version = EXCLUDED.version,
                    updated_at = now()
                WHERE events.version < EXCLUDED.version
                """)
                .param("storyId", m.storyId()).param("companyId", m.companyId())
                .param("companyName", m.companyName()).param("symbol", m.symbol())
                .param("exchange", m.exchange()).param("currency", m.currency())
                .param("storyTitle", m.storyTitle()).param("firstSeen", firstSeenUtc(m.firstSeen()))
                .param("impactScore", m.impactScore())
                .param("articleCount", m.articleCount() == null ? null : m.articleCount().intValue())
                .param("sentiment", m.sentiment())
                .param("topics", toPgArray(m.topics()))
                .param("version", m.version())
                .update() > 0;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM events").query(Long.class).single();
    }

    /** Keys (storyId, companyId) of stored events whose version is older than {@code version}. */
    public List<List<String>> keysWithVersionBefore(final long version) {
        return jdbc.sql("SELECT story_id, company_id FROM events WHERE version < ?")
                .param(version)
                .query((rs, i) -> List.of(rs.getString(1), rs.getString(2)))
                .list();
    }

    public Optional<StoredEvent> find(final String storyId, final String companyId) {
        return jdbc.sql("SELECT story_id, company_id, symbol, exchange, first_seen, version FROM events WHERE story_id = ? AND company_id = ?")
                .params(storyId, companyId)
                .query((rs, i) -> new StoredEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getLong(6)))
                .optional();
    }

    /** Neo4j's toString(datetime) can carry a zone id suffix ("...Z[UTC]"); Postgres wants plain ISO. */
    static String firstSeenUtc(final String raw) {
        if (raw == null) {
            return null;
        }
        final int bracket = raw.indexOf('[');
        return bracket > 0 ? raw.substring(0, bracket) : raw;
    }

    static String toPgArray(final List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        final StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
