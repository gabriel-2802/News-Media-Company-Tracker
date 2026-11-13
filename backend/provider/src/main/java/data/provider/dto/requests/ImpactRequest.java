package data.provider.dto.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Market reaction to an event, measured by the analytics service (market-model event study).")
public record ImpactRequest(
        @Schema(description = "Benchmark used for the market model.", example = "KBE") @NotNull @NotEmpty String benchmark,
        @Schema(description = "Abnormal return on the event day (fraction, 0.012 = +1.2%).", example = "-0.018") Double abnormalReturn,
        @Schema(description = "Cumulative abnormal return over trading days [-1, +1].", example = "-0.031") Double car1,
        @Schema(description = "Cumulative abnormal return over trading days [-5, +5]; null until available.") Double car5,
        @Schema(description = "Market-model beta over the estimation window.", example = "1.12") Double beta,
        @Schema(description = "Two-sided p-value of the [-1, +1] CAR.", example = "0.04") Double pValue,
        @Schema(description = "Trading days in the estimation window.", example = "220") @NotNull @Min(1) Integer estimationDays) {}
