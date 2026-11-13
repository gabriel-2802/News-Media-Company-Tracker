package news.media.monitor.manager.dto.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * event.upserted from the provider: a Story-[:AFFECTS]->Company event was created, changed or
 * (deleted = true) removed. Same shape the analytics service consumes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventMessage(
        String storyId,
        String companyId,
        String companyName,
        String symbol,
        String exchange,
        String currency,
        String storyTitle,
        String firstSeen,
        Double impactScore,
        Integer articleCount,
        Double sentiment,
        List<String> topics,
        boolean deleted,
        long version
) {}
