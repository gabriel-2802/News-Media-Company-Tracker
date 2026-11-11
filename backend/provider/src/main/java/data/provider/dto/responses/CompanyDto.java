package data.provider.dto.responses;

import data.provider.models.Company;
import data.provider.models.CompanyKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Comparator;
import java.util.List;

@Schema(description = "A company, regulator or central bank that articles can mention.")
public record CompanyDto(
        @Schema(description = "Stable id (UUID).") String id,
        @Schema(description = "Display name.", example = "JPMorgan Chase") String name,
        @Schema(description = "Kind of organisation.", example = "BANK") CompanyKind kind,
        @Schema(description = "ISO 3166-1 alpha-2 country (EU/INT for supranational bodies).", example = "US") String country,
        @Schema(description = "Legal Entity Identifier, when verified.", example = "8I5DZWZKVSZI1NUHU748") String lei,
        @Schema(description = "Alternative names used for entity linking.") List<String> aliases,
        @Schema(description = "Listings, primary first.") List<TickerDto> tickers) {

    @Schema(description = "A listing of the company.")
    public record TickerDto(
            @Schema(example = "JPM") String symbol,
            @Schema(description = "ISO 10383 MIC.", example = "XNYS") String exchange,
            @Schema(example = "USD") String currency,
            @Schema(description = "Listing used for charts.") boolean primary) {}

    public CompanyDto(Company c) {
        this(c.getId(), c.getName(), c.getKind(), c.getCountry(), c.getLei(),
                c.getAliases() == null ? List.of() : c.getAliases(),
                c.getListings() == null ? List.of() : c.getListings().stream()
                        .filter(l -> l.getTicker() != null)
                        .sorted(Comparator.comparing(l -> !Boolean.TRUE.equals(l.getPrimary())))
                        .map(l -> new TickerDto(l.getTicker().getSymbol(), l.getTicker().getExchange(),
                                l.getTicker().getCurrency(), Boolean.TRUE.equals(l.getPrimary())))
                        .toList());
    }
}
