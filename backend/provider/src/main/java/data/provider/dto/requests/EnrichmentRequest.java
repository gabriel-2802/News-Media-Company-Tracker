package data.provider.dto.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

@Schema(description = "Enricher output for one article: overall sentiment and company mentions. Replaces any previous enrichment.")
public record EnrichmentRequest(
        @Schema(description = "Canonical URL of the article.", example = "https://www.bankingdive.com/news/example/")
        @NotNull @NotEmpty
        String url,

        @Schema(description = "Sentiment label of title + lead.", example = "negative")
        @NotNull @Pattern(regexp = "positive|negative|neutral")
        String sentimentLabel,

        @Schema(description = "Signed sentiment in [-1, 1] (P(positive) - P(negative)).", example = "-0.62")
        @NotNull @DecimalMin("-1.0") @DecimalMax("1.0")
        Double sentimentScore,

        @Schema(description = "Companies mentioned in the article (may be empty).")
        @NotNull @Valid
        List<Mention> mentions) {

    @Schema(description = "One linked company mention.")
    public record Mention(
            @Schema(description = "Company id (from GET /api/companies).") @NotNull @NotEmpty String companyId,
            @Schema(description = "Number of mentions in title + body.", example = "3") @NotNull @Min(1) Integer count,
            @Schema(description = "How central the company is to the article, in [0, 1].", example = "0.9")
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double salience,
            @Schema(description = "Whether the company is named in the title.") @NotNull Boolean inTitle,
            @Schema(description = "Average sentiment of sentences mentioning the company, in [-1, 1]; null if unknown.")
            @DecimalMin("-1.0") @DecimalMax("1.0") Double sentiment) {}
}
