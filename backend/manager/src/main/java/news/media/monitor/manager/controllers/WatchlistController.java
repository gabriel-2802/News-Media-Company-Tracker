package news.media.monitor.manager.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import news.media.monitor.manager.dto.requests.WatchlistRequest;
import news.media.monitor.manager.dto.responses.WatchlistItemResponse;
import news.media.monitor.manager.services.WatchlistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/me/watchlist")
@RequiredArgsConstructor
@Tag(name = "Watchlist", description = "Tickers the current user watches, with event-alert settings")
@SecurityRequirement(name = "bearerAuth")
public class WatchlistController {

    /** Up to 20 chars; one inner space for listings like Nordea's "NDA FI" (Helsinki). */
    private static final String SYMBOL = "(?=.{1,20}$)[A-Za-z0-9.\\-]+(?: [A-Za-z0-9.\\-]+)?";

    private final WatchlistService watchlistService;

    @GetMapping
    @Operation(summary = "Watched tickers",
            description = "Oldest first, with alert settings, the latest quote and the latest event from the analytics "
                    + "service. If analytics is unavailable the list is still returned, with quote and latestEvent null.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Watchlist returned"),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
            })
    public ResponseEntity<List<WatchlistItemResponse>> list(@AuthenticationPrincipal String email) {
        return ResponseEntity.ok(watchlistService.list(email));
    }

    @PutMapping("/{symbol}")
    @Operation(summary = "Watch a ticker or change its alert settings",
            description = "Adds the symbol (validated against the provider's companies) or updates minImpact/topics. "
                    + "The body is optional; omitted fields keep their value (new entries: minImpact 0.5, any topic). "
                    + "An alert notification (type TICKER_ALERT) is created once per event (story + company) whose "
                    + "impact score reaches minImpact and that has one of the topics, if any are set.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Ticker added"),
                    @ApiResponse(responseCode = "200", description = "Settings updated"),
                    @ApiResponse(responseCode = "400", description = "Invalid symbol, threshold or topic"),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token"),
                    @ApiResponse(responseCode = "404", description = "Unknown ticker symbol"),
                    @ApiResponse(responseCode = "409", description = "Watchlist already holds 50 tickers"),
                    @ApiResponse(responseCode = "502", description = "The news-provider service is unavailable")
            })
    public ResponseEntity<WatchlistItemResponse> put(
            @AuthenticationPrincipal String email,
            @Parameter(description = "Ticker symbol", example = "JPM") @PathVariable @Pattern(regexp = SYMBOL) String symbol,
            @Valid @RequestBody(required = false) WatchlistRequest request) {
        WatchlistService.Result result = watchlistService.put(email, symbol, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.item());
    }

    @DeleteMapping("/{symbol}")
    @Operation(summary = "Stop watching a ticker",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Removed"),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token"),
                    @ApiResponse(responseCode = "404", description = "Ticker not on the watchlist")
            })
    public ResponseEntity<Void> remove(@AuthenticationPrincipal String email,
                                       @PathVariable @Pattern(regexp = SYMBOL) String symbol) {
        watchlistService.remove(email, symbol);
        return ResponseEntity.noContent().build();
    }
}
