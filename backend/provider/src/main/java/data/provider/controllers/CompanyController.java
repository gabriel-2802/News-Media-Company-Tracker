package data.provider.controllers;

import data.provider.dto.responses.CompanyDto;
import data.provider.dto.responses.GraphDto;
import data.provider.models.CompanyKind;
import data.provider.services.CompanyService;
import data.provider.util.Constants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(Constants.COMPANIES_BASE_PATH)
@Tag(name = "Companies", description = "Banks, fintechs, regulators and central banks that articles are linked to.")
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping("/{symbol}/graph")
    @Operation(summary = "Graph around a company",
            description = "Node-link JSON: the company's stories first seen in the last `days` days (highest impact first) "
                    + "and every company those stories affect. Edges are AFFECTS events with impact, sentiment and CAR[-1,+1].")
    @ApiResponse(responseCode = "200", description = "Graph (empty when the company has no recent stories)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = GraphDto.class)))
    @ApiResponse(responseCode = "400", description = "No company listed under this symbol")
    public ResponseEntity<GraphDto> graph(
            @PathVariable final String symbol,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) final int days,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) final int limit) {
        return ResponseEntity.ok(companyService.graph(symbol, days, limit));
    }

    @GetMapping
    @Operation(summary = "List companies",
            description = "Returns the whole company universe (a few hundred entries) with aliases and listings. "
                    + "Used by the enricher for entity linking and by analytics for the ticker universe.")
    @ApiResponse(responseCode = "200", description = "Companies retrieved",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = CompanyDto.class))))
    public ResponseEntity<List<CompanyDto>> getCompanies(
            @Parameter(description = "Only this kind (BANK, FINTECH, REGULATOR, CENTRAL_BANK, OTHER).", example = "BANK")
            @RequestParam(required = false) final CompanyKind kind) {
        return ResponseEntity.ok(companyService.getCompanies(kind));
    }
}
