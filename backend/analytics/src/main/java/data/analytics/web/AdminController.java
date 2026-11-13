package data.analytics.web;

import data.analytics.events.EventRebuildService;
import data.analytics.market.CompanyDirectory;
import data.analytics.market.PriceIngestService;
import data.analytics.study.ImpactService;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "Admin", description = "Run analytics jobs on demand.")
public class AdminController {

    private final EventRebuildService rebuild;
    private final PriceIngestService ingest;
    private final ImpactService impact;
    private final CompanyDirectory directory;

    @PostMapping("/events/rebuild")
    @Operation(summary = "Rebuild the event read model from the provider's GET /api/events")
    public ResponseEntity<Map<String, Object>> rebuildEvents(@RequestParam(required = false) final String since) {
        return ResponseEntity.ok(Map.of("applied", rebuild.rebuild(since)));
    }

    @PostMapping("/prices/ingest")
    @Operation(summary = "Fetch new daily bars for all covered listings and benchmarks now")
    public ResponseEntity<PriceIngestService.Summary> ingestPrices() {
        return ResponseEntity.ok(ingest.ingest());
    }

    @PostMapping("/companies/refresh")
    @Operation(summary = "Refresh the local company directory (names for charts) from the provider")
    public ResponseEntity<Map<String, Object>> refreshCompanies() {
        return ResponseEntity.ok(Map.of("listings", directory.refreshFromProvider()));
    }

    @PostMapping("/impact/run")
    @Operation(summary = "Run pending event studies now")
    public ResponseEntity<ImpactService.Summary> runImpact() {
        return ResponseEntity.ok(impact.run());
    }
}
