package news.media.monitor.manager.dto.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "A watched ticker with its alert settings, latest quote and latest event.")
public record WatchlistItemResponse(
        @Schema(description = "Id of the underlying TICKER subscription.") String subscriptionId,
        @Schema(example = "JPM") String symbol,
        @Schema(example = "JPMorgan Chase") String name,
        @Schema(description = "Effective impact threshold.") double minImpact,
        List<String> topics,
        Instant createdAt,
        @Schema(description = "Null when the analytics service has no price for the symbol or is unavailable.") Quote quote,
        @Schema(description = "Null when there is no event yet or analytics is unavailable.") LatestEvent latestEvent
) {
    public record Quote(String exchange, String currency, long time, double last, Double previousClose,
                        Double change, Double changePct) {}

    public record LatestEvent(String storyId, String companyId, String title, long firstSeen, Double impactScore,
                              Double sentiment, Integer articleCount, List<String> topics) {}
}
