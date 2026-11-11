package data.provider.dto.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Request to replace an article's topics with a scored, multi-label set.")
public record TopicsSetRequest(
        @Schema(description = "Canonical URL of the article to tag.", example = "https://www.bankingdive.com/news/example/")
        @NotNull @NotEmpty
        String url,

        @Schema(description = "Topics with classifier confidence. Replaces all existing topics.")
        @NotNull @NotEmpty @Valid
        List<TopicScore> topics) {

    @Schema(description = "One topic and its confidence.")
    public record TopicScore(
            @Schema(description = "Topic name (must already exist).", example = "regulation_legal_enforcement")
            @NotNull @NotEmpty
            String name,

            @Schema(description = "Classifier confidence in [0, 1].", example = "0.87")
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0")
            Double score) {}
}
