package news.media.monitor.manager.dto.responses;

import news.media.monitor.manager.models.Subscription;
import news.media.monitor.manager.models.SubscriptionType;

import java.time.Instant;
import java.util.List;

/** minImpact and topics are set for TICKER subscriptions only. */
public record SubscriptionResponse(
        String id,
        SubscriptionType type,
        String targetId,
        Instant createdAt,
        Double minImpact,
        List<String> topics
) {
    public static SubscriptionResponse from(Subscription s) {
        return new SubscriptionResponse(s.getId(), s.getType(), s.getTargetId(), s.getCreatedAt(),
                s.getMinImpact(), s.getType() == SubscriptionType.TICKER ? s.getTopics() : null);
    }
}
