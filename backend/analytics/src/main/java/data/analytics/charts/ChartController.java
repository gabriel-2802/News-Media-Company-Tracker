package data.analytics.charts;

import data.analytics.charts.ChartDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Read-only chart APIs. Public, cached for 60 s, with ETags (304 when unchanged).
 * Dates are ISO yyyy-MM-dd; omitted, the range is the 12 months up to the latest price.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/charts")
@Tag(name = "Charts", description = "Price, event-marker, sentiment and market-impact series for charts.")
public class ChartController {

    private static final int MAX_SPAN_DAYS = 5 * 366;
    private static final CacheControl CACHE = CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic();

    private final ChartService charts;
    private final JdbcClient jdbc;

    private record Range(LocalDate from, LocalDate to) {}

    private Range range(final LocalDate from, final LocalDate to) {
        final LocalDate end = to != null ? to : jdbc.sql("SELECT max(day) FROM prices_daily").query(LocalDate.class)
                .optional().orElse(LocalDate.now(ZoneOffset.UTC));
        final LocalDate start = from != null ? from : end.minusYears(1);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        if (ChartService.days(start, end) > MAX_SPAN_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "range longer than 5 years");
        }
        return new Range(start, end);
    }

    private static ChartService.Bucket bucket(final String b) {
        try {
            return ChartService.Bucket.valueOf(b.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bucket must be day or week");
        }
    }

    private static List<String> csv(final String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split("\\s*,\\s*"));
    }

    private static <T> ResponseEntity<T> ok(final T body) {
        return ResponseEntity.ok().cacheControl(CACHE).body(body);
    }

    @GetMapping("/companies/{symbol}/price")
    @Operation(summary = "Daily OHLCV of a listing", description = "Bars aggregated into at most maxPoints buckets when the range is longer.")
    public ResponseEntity<PriceSeries> price(
            @PathVariable final String symbol,
            @Parameter(description = "ISO 10383 MIC; needed only if the symbol trades on several exchanges.") @RequestParam(required = false) final String exchange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(defaultValue = "2000") @Min(50) @Max(5000) final int maxPoints) {
        final Range r = range(from, to);
        return ok(charts.price(charts.listing(symbol, exchange), r.from(), r.to(), maxPoints));
    }

    @GetMapping("/companies/{symbol}/events")
    @Operation(summary = "Event markers of a listing", description = "Ready for setMarkers(); placed on the first trading day on/after the event.")
    public ResponseEntity<MarkerSeries> events(
            @PathVariable final String symbol,
            @RequestParam(required = false) final String exchange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @Parameter(description = "Comma-separated topic names; any match.") @RequestParam(required = false) final String topics,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1) final double minImpact) {
        final Range r = range(from, to);
        return ok(charts.events(charts.listing(symbol, exchange), r.from(), r.to(), csv(topics), minImpact));
    }

    @GetMapping("/companies/{symbol}/overlay")
    @Operation(summary = "Price bars and event markers in one call", description = "The main investor chart.")
    public ResponseEntity<Overlay> overlay(
            @PathVariable final String symbol,
            @RequestParam(required = false) final String exchange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(defaultValue = "2000") @Min(50) @Max(5000) final int maxPoints,
            @RequestParam(required = false) final String topics,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1) final double minImpact) {
        final Range r = range(from, to);
        return ok(charts.overlay(charts.listing(symbol, exchange), r.from(), r.to(), maxPoints, csv(topics), minImpact));
    }

    @GetMapping("/companies/{symbol}/sentiment")
    @Operation(summary = "Company sentiment per day or week", description = "Article-weighted mean of the company's event sentiment.")
    public ResponseEntity<List<SentimentPoint>> sentiment(
            @PathVariable final String symbol, @RequestParam(required = false) final String exchange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(defaultValue = "day") final String bucket) {
        final Range r = range(from, to);
        return ok(charts.sentiment(charts.listing(symbol, exchange), r.from(), r.to(), bucket(bucket)));
    }

    @GetMapping("/companies/{symbol}/news-volume")
    @Operation(summary = "Events and articles about a company per day or week")
    public ResponseEntity<List<VolumePoint>> newsVolume(
            @PathVariable final String symbol, @RequestParam(required = false) final String exchange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(defaultValue = "day") final String bucket) {
        final Range r = range(from, to);
        return ok(charts.newsVolume(charts.listing(symbol, exchange), r.from(), r.to(), bucket(bucket)));
    }

    @GetMapping("/events/{storyId}/{companyId}/impact")
    @Operation(summary = "Event study with the abnormal-return path over trading days [-5, +5]")
    public ResponseEntity<EventImpact> impact(@PathVariable final String storyId, @PathVariable final String companyId) {
        return ok(charts.impact(storyId, companyId));
    }

    @GetMapping("/topics/volume")
    @Operation(summary = "Events per topic per day or week", description = "An event counts under each of its topics.")
    public ResponseEntity<List<TopicVolumePoint>> topicVolume(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to,
            @RequestParam(defaultValue = "week") final String bucket,
            @RequestParam(required = false) final String topics) {
        final Range r = range(from, to);
        return ok(charts.topicVolume(r.from(), r.to(), bucket(bucket), csv(topics)));
    }

    @GetMapping("/sector/heatmap")
    @Operation(summary = "Return, sentiment and news per listing over a window",
            description = "Window ends at `to` (default: the latest price day). Rows with the most events first.")
    public ResponseEntity<Heatmap> heatmap(
            @RequestParam(defaultValue = "7d") final String window,
            @Parameter(description = "Window end (ISO date); default the latest price day.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to) {
        final int days = switch (window) {
            case "1d" -> 1;
            case "7d" -> 7;
            case "30d" -> 30;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window must be 1d, 7d or 30d");
        };
        return ok(charts.heatmap(days, to));
    }

    @GetMapping("/quotes")
    @Operation(summary = "Latest close and change for several symbols", description = "Unknown or ambiguous symbols are left out.")
    public ResponseEntity<List<Quote>> quotes(@RequestParam @Size(min = 1, max = 50) final List<String> symbols) {
        return ok(charts.quotes(symbols));
    }

    @GetMapping("/latest-events")
    @Operation(summary = "Most recent event per symbol", description = "For watchlists; symbols without events are left out.")
    public ResponseEntity<List<LatestEvent>> latestEvents(@RequestParam @Size(min = 1, max = 50) final List<String> symbols) {
        return ok(charts.latestEvents(symbols));
    }
}
