package data.analytics.web;

import data.analytics.events.EventRebuildService;
import data.analytics.market.CompanyDirectory;
import data.analytics.market.PriceIngestService;
import data.analytics.study.ImpactService;
import data.analytics.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Manual triggers for the scheduled jobs (ROLE_ADMIN / ROLE_SYSTEM). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
@Tag(name = "Admin")                                       // description in OpenApiConfig
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@ApiResponse(responseCode = "401", description = "Missing, invalid or expired token")
@ApiResponse(responseCode = "403", description = "Token lacks ROLE_ADMIN or ROLE_SYSTEM")
public class AdminController {

    private final EventRebuildService rebuild;
    private final PriceIngestService ingest;
    private final ImpactService impact;
    private final CompanyDirectory directory;

    @PostMapping("/events/rebuild")
    @Operation(summary = "Rebuild the event read model from the provider's GET /api/events",
            description = "Idempotent. Without `since` it also removes stored events that are missing from the provider's "
                    + "full feed (older than the rebuild; never on an empty feed). Returns the number of changes.")
    @ApiResponse(responseCode = "200", description = "Rebuild done: {\"applied\": n}")
    public ResponseEntity<Map<String, Object>> rebuildEvents(
            @Parameter(description = "Only replay events updated since this ISO-8601 instant, e.g. 2026-11-01T00:00:00Z. "
                    + "Omit for a full rebuild with reconciliation.")
            @RequestParam(required = false) final String since) {
        return ResponseEntity.ok(Map.of("applied", rebuild.rebuild(since)));
    }

    @PostMapping("/prices/ingest")
    @Operation(summary = "Fetch new daily bars for all covered listings and benchmarks now",
            description = "Incremental; uses market-data API credits (Twelve Data). Normally runs after the US close.")
    @ApiResponse(responseCode = "200", description = "Ingest summary")
    public ResponseEntity<PriceIngestService.Summary> ingestPrices() {
        return ResponseEntity.ok(ingest.ingest());
    }

    @PostMapping("/companies/refresh")
    @Operation(summary = "Refresh the local company directory (names for charts) from the provider",
            description = "An empty answer from the provider leaves the directory unchanged.")
    @ApiResponse(responseCode = "200", description = "Listings stored: {\"listings\": n}")
    public ResponseEntity<Map<String, Object>> refreshCompanies() {
        return ResponseEntity.ok(Map.of("listings", directory.refreshFromProvider()));
    }

    @PostMapping("/impact/run")
    @Operation(summary = "Run pending event studies now",
            description = "Market-model event studies for listed events whose prices exist; results are written back to "
                    + "the provider first and stored only if that succeeded. Normally runs every 30 minutes.")
    @ApiResponse(responseCode = "200", description = "Run summary (candidates, computed, pending, noBenchmark, failed)")
    public ResponseEntity<ImpactService.Summary> runImpact() {
        return ResponseEntity.ok(impact.run());
    }
}
