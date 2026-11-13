package data.analytics.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Mirror of the provider's event.upserted message (data.provider.dto.messages.EventMessage). */
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
        Long articleCount,
        Double sentiment,
        List<String> topics,
        boolean deleted,
        long version) {}
