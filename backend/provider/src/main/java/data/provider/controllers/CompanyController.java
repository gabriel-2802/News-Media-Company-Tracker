package data.provider.controllers;

import data.provider.dto.responses.CompanyDto;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(Constants.COMPANIES_BASE_PATH)
@Tag(name = "Companies", description = "Banks, fintechs, regulators and central banks that articles are linked to.")
public class CompanyController {

    private final CompanyService companyService;

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
