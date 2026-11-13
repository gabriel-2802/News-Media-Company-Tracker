package data.provider.dto.messages;

import java.util.List;
import java.util.Map;

/**
 * Published on the news_monitor exchange with routing key event.upserted
 * whenever a (:Story)-[:AFFECTS]->(:Company) event is created, updated or
 * removed. Consumers keep the highest `version` per (storyId, companyId).
 */
public record EventMessage(
        String storyId,
        String companyId,
        String companyName,
        String symbol,          // primary listing, null for unlisted companies
        String exchange,
        String currency,
        String storyTitle,
        String firstSeen,       // ISO-8601 UTC
        Double impactScore,
        Long articleCount,
        Double sentiment,
        List<String> topics,
        boolean deleted,
        long version) {

    @SuppressWarnings("unchecked")
    public static EventMessage fromRow(final Map<String, Object> row, final boolean deleted) {
        return new EventMessage(
                (String) row.get("storyId"), (String) row.get("companyId"), (String) row.get("companyName"),
                (String) row.get("symbol"), (String) row.get("exchange"), (String) row.get("currency"),
                (String) row.get("storyTitle"), row.get("firstSeen") == null ? null : row.get("firstSeen").toString(),
                (Double) row.get("impactScore"), (Long) row.get("articleCount"), (Double) row.get("sentiment"),
                row.get("topics") == null ? List.of() : (List<String>) row.get("topics"),
                deleted, ((Number) row.get("version")).longValue());
    }
}
