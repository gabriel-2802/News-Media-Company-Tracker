package news.media.monitor.manager.dto.responses;

import news.media.monitor.manager.models.Notification;
import news.media.monitor.manager.models.NotificationType;

import java.time.Instant;

/** symbol, storyId, companyId, impactScore and sentiment are set for TICKER_ALERT notifications only. */
public record NotificationResponse(
        String id,
        NotificationType type,
        String message,
        boolean seen,
        Instant createdAt,
        String symbol,
        String storyId,
        String companyId,
        Double impactScore,
        Double sentiment
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getMessage(), n.isSeen(), n.getCreatedAt(),
                n.getSymbol(), n.getStoryId(), n.getCompanyId(), n.getImpactScore(), n.getSentiment());
    }
}
