package data.provider.dto.requests;

import data.provider.models.Region;
import data.provider.models.SourceCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Payload for registering or updating a news source to scrape.")
public record NewsSourceRequest(

        @NotNull @NotEmpty
        @Schema(description = "Unique, human-readable name for the source.", example = "banking-dive",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @NotNull @NotEmpty
        @Schema(description = "Base URL of the source's website. Articles must be hosted under this URL.",
                example = "https://www.bankingdive.com", requiredMode = Schema.RequiredMode.REQUIRED)
        String baseUrl,

        @NotNull @NotEmpty
        @Schema(description = "RSS feed URL used to discover new articles for this source.",
                example = "https://www.bankingdive.com/feeds/news/", requiredMode = Schema.RequiredMode.REQUIRED)
        String rssUrl,

        @NotNull
        @Schema(description = "Kind of publisher.", example = "TRADE_PRESS",
                requiredMode = Schema.RequiredMode.REQUIRED)
        SourceCategory category,

        @NotNull
        @Schema(description = "Primary market the source covers.", example = "US",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Region region,

        @Schema(description = "Whether full article bodies sit behind a paywall (headline-only ingestion).",
                example = "false", defaultValue = "false")
        Boolean paywalled,

        @Schema(description = "ISO 639-1 language code of the source.", example = "en", defaultValue = "en")
        String language,

        @Min(5) @Max(1440)
        @Schema(description = "Minutes between scheduled scrapes of this source.", example = "30", defaultValue = "60")
        Integer scrapeIntervalMinutes,

        @Schema(description = "Free-text notes about this source.")
        String notes

) {}
