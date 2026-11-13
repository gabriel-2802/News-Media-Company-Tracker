package news.media.monitor.manager.dto.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Alert settings of a watched ticker. Omitted fields keep their current value (or the default).")
public record WatchlistRequest(
        @Schema(description = "Alert when an event's impact score is at least this (0-1). Default 0.5.", example = "0.6")
        @DecimalMin("0.0") @DecimalMax("1.0") Double minImpact,
        @Schema(description = "Alert only for events with one of these topics; empty = any topic.",
                example = "[\"regulation_legal_enforcement\", \"earnings_results\"]")
        @Size(max = 20) List<@Pattern(regexp = "[a-z0-9_]{1,64}") String> topics
) {}
