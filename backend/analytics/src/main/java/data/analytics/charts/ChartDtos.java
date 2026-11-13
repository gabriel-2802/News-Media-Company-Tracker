package data.analytics.charts;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Response shapes for /api/charts. Times are unix seconds (UTC midnight for
 * daily data), so arrays can be passed to TradingView Lightweight Charts
 * (setData / setMarkers) or ECharts without conversion.
 */
public final class ChartDtos {

    private ChartDtos() {
    }

    @Schema(description = "A listing (symbol on an exchange).")
    public record Listing(String symbol, String exchange, String currency, String name, String companyId) {}

    @Schema(description = "Daily OHLCV bar.")
    public record BarPoint(long time, Double open, Double high, Double low, double close, Long volume) {}

    @Schema(description = "Price series of a listing.")
    public record PriceSeries(Listing listing, List<BarPoint> bars, boolean downsampled) {}

    @Schema(description = "Event marker, ready for setMarkers(); extra fields describe the event.")
    public record Marker(
            @Schema(description = "First trading day on/after the event (event date if no bars).") long time,
            @Schema(example = "aboveBar") String position,
            @Schema(example = "arrowDown") String shape,
            @Schema(example = "#d32f2f") String color,
            @Schema(description = "Short label.") String text,
            String storyId, String companyId, String title, List<String> topics,
            Double impactScore, Double sentiment, Integer articleCount,
            @Schema(description = "Cumulative abnormal return over trading days [-1, +1], when computed.") Double car1,
            Double car5, Double pValue,
            @Schema(description = "high (impact >= 0.7), medium (>= 0.4) or low.") String severity) {}

    public record MarkerSeries(Listing listing, List<Marker> markers) {}

    public record Overlay(Listing listing, List<BarPoint> bars, List<Marker> markers, boolean downsampled) {}

    @Schema(description = "Company sentiment per bucket: article-weighted mean over the company's events.")
    public record SentimentPoint(long time, double avgSentiment, int events, int articles) {}

    public record VolumePoint(long time, int events, int articles) {}

    @Schema(description = "Events per topic in one bucket (an event can count under several topics).")
    public record TopicVolumePoint(long time, Map<String, Integer> topics) {}

    @Schema(description = "One day of the event window.")
    public record ImpactPathPoint(int offset, long time, double stockReturn, double benchmarkReturn, double ar, double car) {}

    @Schema(description = "Event study of one event with its abnormal-return path over trading days [-5, +5].")
    public record EventImpact(String storyId, String companyId, String title, Listing listing, String benchmark,
                              long eventDay, double alpha, double beta, double ar0, Double car1, Double car5,
                              Double pValue, int estimationDays, boolean complete, List<ImpactPathPoint> path) {}

    @Schema(description = "One heatmap row.")
    public record HeatmapRow(Listing listing, Double returnPct, Double avgSentiment, int events, String topEventTitle,
                             Double topEventImpact) {}

    public record Heatmap(String window, long from, long to, List<HeatmapRow> rows) {}

    @Schema(description = "Latest close and change versus the previous close.")
    public record Quote(Listing listing, long time, double last, Double previousClose, Double change, Double changePct) {}

    @Schema(description = "Most recent event of a symbol (by first-seen time).")
    public record LatestEvent(String symbol, String exchange, String storyId, String companyId, String title,
                              long firstSeen, Double impactScore, Double sentiment, Integer articleCount,
                              List<String> topics) {}
}
