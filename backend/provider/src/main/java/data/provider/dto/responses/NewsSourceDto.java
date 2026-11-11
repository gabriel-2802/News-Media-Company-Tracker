package data.provider.dto.responses;

import data.provider.models.NewsSource;
import data.provider.models.Region;
import data.provider.models.SourceCategory;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A registered news source and its current stats.")
public record NewsSourceDto(

        @Schema(description = "Unique, human-readable name for the source.", example = "banking-dive")
        String name,

        @Schema(description = "Base URL of the source's website.", example = "https://www.bankingdive.com")
        String baseUrl,

        @Schema(description = "RSS feed URL used to discover new articles for this source.",
                example = "https://www.bankingdive.com/feeds/news/")
        String rssUrl,

        @Schema(description = "Kind of publisher.", example = "TRADE_PRESS")
        SourceCategory category,

        @Schema(description = "Primary market the source covers.", example = "US")
        Region region,

        @Schema(description = "Whether full article bodies sit behind a paywall.", example = "false")
        boolean paywalled,

        @Schema(description = "ISO 639-1 language code of the source.", example = "en")
        String language,

        @Schema(description = "Number of consecutive scrape failures recorded for this source.", example = "0")
        int failureCount,

        @Schema(description = "Whether the source has been disabled from further scraping.", example = "false")
        boolean disabled,

        @Schema(description = "Total number of articles ingested from this source.", example = "42")
        Long articleCount,

        @Schema(description = "Free-text notes about this source.")
        String notes) {

    public NewsSourceDto(NewsSource newsSource, long articleCount) {
        this(newsSource.getName(),
                newsSource.getBaseUrl(),
                newsSource.getRssUrl(),
                newsSource.getCategory(),
                newsSource.getRegion(),
                Boolean.TRUE.equals(newsSource.getPaywalled()),
                newsSource.getLanguage(),
                newsSource.getFailureCount(),
                newsSource.getIsDisabled(),
                articleCount,
                newsSource.getNotes());
    }
}
