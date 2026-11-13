package data.analytics.charts;

import data.analytics.charts.ChartDtos.*;
import data.analytics.config.AnalyticsProperties;
import data.analytics.market.Bar;
import data.analytics.market.PriceRepository;
import data.analytics.study.EventStudy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class ChartService {

    public enum Bucket { DAY, WEEK }

    private record EventRow(String storyId, String companyId, String title, List<String> topics, OffsetDateTime firstSeen,
                            Double impact, Double sentiment, Integer articles, Double car1, Double car5, Double p) {}

    private final JdbcClient jdbc;
    private final PriceRepository prices;
    private final AnalyticsProperties props;

    // ---------------------------------------------------------------- listings

    public Listing listing(final String symbol, final String exchange) {
        final List<Listing> found = jdbc.sql("""
                SELECT symbol, exchange, max(currency) AS currency, max(name) AS name, max(company_id) AS company_id FROM (
                    SELECT symbol, exchange, currency, name, company_id FROM companies WHERE symbol = :s
                    UNION ALL SELECT symbol, exchange, currency, company_name, company_id FROM events WHERE symbol = :s
                    UNION ALL SELECT DISTINCT symbol, exchange, NULL, NULL, NULL FROM prices_daily WHERE symbol = :s
                ) t WHERE (CAST(:e AS text) IS NULL OR exchange = :e) GROUP BY symbol, exchange
                """).param("s", symbol).param("e", exchange)
                .query((rs, i) -> new Listing(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown listing " + symbol + (exchange == null ? "" : "@" + exchange));
        }
        if (found.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, symbol + " trades on several exchanges "
                    + found.stream().map(Listing::exchange).toList() + "; pass exchange");
        }
        return found.getFirst();
    }

    // ---------------------------------------------------------------- price / events / overlay

    public PriceSeries price(final Listing l, final LocalDate from, final LocalDate to, final int maxPoints) {
        final List<Bar> bars = prices.range(l.symbol(), l.exchange(), from, to);
        final List<BarPoint> points = downsample(bars, maxPoints);
        return new PriceSeries(l, points, points.size() < bars.size());
    }

    public MarkerSeries events(final Listing l, final LocalDate from, final LocalDate to, final List<String> topics,
                               final double minImpact) {
        final NavigableSet<LocalDate> tradingDays = new TreeSet<>(
                prices.range(l.symbol(), l.exchange(), from, to.plusDays(10)).stream().map(Bar::day).toList());
        final List<Marker> markers = eventRows(l, from, to).stream()
                .filter(e -> e.impact() == null || e.impact() >= minImpact)
                .filter(e -> topics.isEmpty() || e.topics().stream().anyMatch(topics::contains))
                .map(e -> marker(e, tradingDays))
                .toList();
        return new MarkerSeries(l, markers);
    }

    public Overlay overlay(final Listing l, final LocalDate from, final LocalDate to, final int maxPoints,
                           final List<String> topics, final double minImpact) {
        final PriceSeries p = price(l, from, to, maxPoints);
        final List<Marker> markers = events(l, from, to, topics, minImpact).markers();
        return new Overlay(l, p.bars(), snapToBars(markers, p.bars()), p.downsampled());
    }

    private List<EventRow> eventRows(final Listing l, final LocalDate from, final LocalDate to) {
        return jdbc.sql("""
                SELECT e.story_id, e.company_id, e.story_title, e.topics, e.first_seen, e.impact_score, e.sentiment,
                       e.article_count, i.car1, i.car5, i.p_value
                FROM events e LEFT JOIN event_impact i ON i.story_id = e.story_id AND i.company_id = e.company_id
                WHERE e.symbol = ? AND e.exchange = ? AND e.first_seen >= ? AND e.first_seen < ?
                ORDER BY e.first_seen
                """).params(l.symbol(), l.exchange(), start(from), start(to.plusDays(1)))
                .query((rs, i) -> new EventRow(rs.getString(1), rs.getString(2), rs.getString(3), strings(rs.getArray(4)),
                        rs.getObject(5, OffsetDateTime.class), (Double) rs.getObject(6), (Double) rs.getObject(7),
                        (Integer) rs.getObject(8), (Double) rs.getObject(9), (Double) rs.getObject(10), (Double) rs.getObject(11)))
                .list();
    }

    private static Marker marker(final EventRow e, final NavigableSet<LocalDate> tradingDays) {
        final LocalDate day = e.firstSeen().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        final LocalDate snapped = tradingDays.ceiling(day) != null ? tradingDays.ceiling(day) : day;
        final double s = e.sentiment() == null ? 0 : e.sentiment();
        final double impact = e.impact() == null ? 0 : e.impact();
        final String severity = impact >= 0.7 ? "high" : impact >= 0.4 ? "medium" : "low";
        final boolean negative = s < -0.15;
        final boolean positive = s > 0.15;
        final String primaryTopic = e.topics().isEmpty() ? "news" : e.topics().getFirst().replace('_', ' ');
        return new Marker(epoch(snapped),
                negative ? "aboveBar" : "belowBar",
                negative ? "arrowDown" : positive ? "arrowUp" : "circle",
                negative ? "#d32f2f" : positive ? "#2e7d32" : "#757575",
                primaryTopic + (e.car1() != null ? String.format(" %+.1f%%", 100 * e.car1()) : ""),
                e.storyId(), e.companyId(), e.title(), e.topics(), e.impact(), e.sentiment(), e.articles(),
                e.car1(), e.car5(), e.p(), severity);
    }

    /** With downsampled bars, move each marker to the bar bucket that contains it. */
    private static List<Marker> snapToBars(final List<Marker> markers, final List<BarPoint> bars) {
        if (bars.isEmpty()) {
            return markers;
        }
        final TreeSet<Long> times = new TreeSet<>(bars.stream().map(BarPoint::time).toList());
        return markers.stream().map(m -> {
            final Long t = times.floor(m.time()) != null ? times.floor(m.time()) : times.first();
            return t == m.time() ? m : new Marker(t, m.position(), m.shape(), m.color(), m.text(), m.storyId(), m.companyId(),
                    m.title(), m.topics(), m.impactScore(), m.sentiment(), m.articleCount(), m.car1(), m.car5(), m.pValue(), m.severity());
        }).toList();
    }

    // ---------------------------------------------------------------- aggregates

    public List<SentimentPoint> sentiment(final Listing l, final LocalDate from, final LocalDate to, final Bucket bucket) {
        final TreeMap<LocalDate, double[]> acc = new TreeMap<>();     // weighted sum, weight, events, articles
        for (EventRow e : eventRows(l, from, to)) {
            final LocalDate b = bucket(e.firstSeen(), bucket);
            final int articles = e.articles() == null ? 1 : Math.max(1, e.articles());
            final double[] a = acc.computeIfAbsent(b, k -> new double[4]);
            if (e.sentiment() != null) {
                a[0] += e.sentiment() * articles;
                a[1] += articles;
            }
            a[2] += 1;
            a[3] += articles;
        }
        return acc.entrySet().stream().map(en -> new SentimentPoint(epoch(en.getKey()),
                en.getValue()[1] == 0 ? 0 : round(en.getValue()[0] / en.getValue()[1]),
                (int) en.getValue()[2], (int) en.getValue()[3])).toList();
    }

    public List<VolumePoint> newsVolume(final Listing l, final LocalDate from, final LocalDate to, final Bucket bucket) {
        return sentiment(l, from, to, bucket).stream().map(p -> new VolumePoint(p.time(), p.events(), p.articles())).toList();
    }

    public List<TopicVolumePoint> topicVolume(final LocalDate from, final LocalDate to, final Bucket bucket, final List<String> only) {
        final TreeMap<LocalDate, Map<String, Integer>> acc = new TreeMap<>();
        jdbc.sql("SELECT first_seen, topics FROM events WHERE first_seen >= ? AND first_seen < ?")
                .params(start(from), start(to.plusDays(1)))
                .query((rs, i) -> Map.entry(rs.getObject(1, OffsetDateTime.class), strings(rs.getArray(2))))
                .list()
                .forEach(e -> {
                    final Map<String, Integer> m = acc.computeIfAbsent(bucket(e.getKey(), bucket), k -> new TreeMap<>());
                    e.getValue().stream().filter(t -> only.isEmpty() || only.contains(t)).forEach(t -> m.merge(t, 1, Integer::sum));
                });
        return acc.entrySet().stream().map(en -> new TopicVolumePoint(epoch(en.getKey()), en.getValue())).toList();
    }

    public Heatmap heatmap(final int days, final LocalDate asOf) {
        final LocalDate to = asOf != null ? asOf : jdbc.sql("SELECT max(day) FROM prices_daily").query(LocalDate.class).optional()
                .orElse(LocalDate.now(ZoneOffset.UTC));
        final LocalDate from = to.minusDays(days);
        final List<HeatmapRow> rows = jdbc.sql("""
                WITH last AS (
                    SELECT DISTINCT ON (symbol, exchange) symbol, exchange, close FROM prices_daily
                    WHERE day <= :to ORDER BY symbol, exchange, day DESC),
                base AS (
                    SELECT DISTINCT ON (symbol, exchange) symbol, exchange, close FROM prices_daily
                    WHERE day <= :from ORDER BY symbol, exchange, day DESC),
                ev AS (
                    SELECT symbol, exchange, count(*) AS n,
                           sum(sentiment * GREATEST(article_count, 1)) / NULLIF(sum(GREATEST(article_count, 1)) FILTER (WHERE sentiment IS NOT NULL), 0) AS sent,
                           (array_agg(story_title ORDER BY impact_score DESC NULLS LAST))[1] AS top_title,
                           max(impact_score) AS top_impact
                    FROM events WHERE first_seen >= :fromTs AND first_seen < :toTs AND symbol IS NOT NULL
                    GROUP BY symbol, exchange)
                SELECT l.symbol, l.exchange, c.currency, c.name, c.company_id,
                       CASE WHEN b.close > 0 THEN 100 * (l.close / b.close - 1) END AS ret,
                       ev.sent, coalesce(ev.n, 0), ev.top_title, ev.top_impact
                FROM last l
                LEFT JOIN base b ON b.symbol = l.symbol AND b.exchange = l.exchange
                LEFT JOIN companies c ON c.symbol = l.symbol AND c.exchange = l.exchange
                LEFT JOIN ev ON ev.symbol = l.symbol AND ev.exchange = l.exchange
                WHERE c.symbol IS NOT NULL
                ORDER BY coalesce(ev.n, 0) DESC, l.symbol
                """).param("to", to).param("from", from).param("fromTs", start(from)).param("toTs", start(to.plusDays(1)))
                .query((rs, i) -> new HeatmapRow(
                        new Listing(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                        roundOrNull((Double) rs.getObject(6)), roundOrNull((Double) rs.getObject(7)), rs.getInt(8),
                        rs.getString(9), (Double) rs.getObject(10)))
                .list();
        return new Heatmap(days + "d", epoch(from), epoch(to), rows);
    }

    public List<Quote> quotes(final List<String> symbols) {
        final List<Quote> out = new ArrayList<>();
        for (String symbol : symbols) {
            final Listing l;
            try {
                l = listing(symbol, null);
            } catch (ResponseStatusException e) {
                continue;                       // unknown or ambiguous symbols are left out of a batch
            }
            final List<Bar> last2 = jdbc.sql("""
                    SELECT day, open, high, low, close, volume FROM prices_daily WHERE symbol = ? AND exchange = ?
                    ORDER BY day DESC LIMIT 2
                    """).params(l.symbol(), l.exchange())
                    .query((rs, i) -> new Bar(rs.getObject(1, LocalDate.class), null, null, null, rs.getDouble(5), null)).list();
            if (last2.isEmpty()) {
                continue;
            }
            final Bar last = last2.getFirst();
            final Double prev = last2.size() > 1 ? last2.get(1).close() : null;
            out.add(new Quote(l, epoch(last.day()), last.close(), prev,
                    prev == null ? null : roundOrNull(last.close() - prev),
                    prev == null || prev == 0 ? null : roundOrNull(100 * (last.close() / prev - 1))));
        }
        return out;
    }

    // ---------------------------------------------------------------- event impact detail

    public EventImpact impact(final String storyId, final String companyId) {
        final Map<String, Object> row = jdbc.sql("""
                SELECT e.story_title, e.symbol, e.exchange, e.currency, e.company_name, e.first_seen, i.benchmark
                FROM events e LEFT JOIN event_impact i ON i.story_id = e.story_id AND i.company_id = e.company_id
                WHERE e.story_id = ? AND e.company_id = ?
                """).params(storyId, companyId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown event " + storyId + "/" + companyId));
        if (row.get("symbol") == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event's company is not listed");
        }
        final String exchange = (String) row.get("exchange");
        final String bench = props.benchmarks().get(exchange);
        if (bench == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No benchmark configured for " + exchange);
        }
        final String benchSymbol = bench.substring(0, bench.lastIndexOf('@'));
        final String benchExchange = bench.substring(bench.lastIndexOf('@') + 1);
        // listOfRows() maps timestamptz to java.sql.Timestamp
        final LocalDate eventDate = ((java.sql.Timestamp) row.get("first_seen")).toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
        final AnalyticsProperties.Study s = props.study();
        final var detailed = new EventStudy(s.estimationStart(), s.estimationEnd(), s.minEstimationDays())
                .runDetailed(prices.range((String) row.get("symbol"), exchange, eventDate.minusDays(420), eventDate.plusDays(20)),
                        prices.range(benchSymbol, benchExchange, eventDate.minusDays(420), eventDate.plusDays(20)), eventDate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not enough prices for this event yet"));
        final EventStudy.Result r = detailed.result();
        return new EventImpact(storyId, companyId, (String) row.get("story_title"),
                new Listing((String) row.get("symbol"), exchange, (String) row.get("currency"), (String) row.get("company_name"), companyId),
                benchSymbol, epoch(r.eventDay()), r.alpha(), r.beta(), r.ar0(), r.car1(), r.car5(), r.pValue(),
                r.estimationDays(), r.complete(),
                detailed.path().stream().map(p -> new ImpactPathPoint(p.offset(), epoch(p.day()), p.stockReturn(),
                        p.benchmarkReturn(), p.ar(), p.car())).toList());
    }

    // ---------------------------------------------------------------- helpers

    /** Aggregate consecutive bars into at most maxPoints buckets (OHLC of the bucket, summed volume). */
    static List<BarPoint> downsample(final List<Bar> bars, final int maxPoints) {
        if (bars.size() <= maxPoints) {
            return bars.stream().map(b -> new BarPoint(epoch(b.day()), b.open(), b.high(), b.low(), b.close(), b.volume())).toList();
        }
        final int size = (int) Math.ceil(bars.size() / (double) maxPoints);
        final List<BarPoint> out = new ArrayList<>();
        for (int i = 0; i < bars.size(); i += size) {
            final List<Bar> chunk = bars.subList(i, Math.min(bars.size(), i + size));
            final Bar first = chunk.getFirst();
            final Bar last = chunk.getLast();
            final Double high = chunk.stream().map(Bar::high).filter(java.util.Objects::nonNull).max(Double::compare).orElse(null);
            final Double low = chunk.stream().map(Bar::low).filter(java.util.Objects::nonNull).min(Double::compare).orElse(null);
            final Long volume = chunk.stream().map(Bar::volume).filter(java.util.Objects::nonNull).reduce(Long::sum).orElse(null);
            out.add(new BarPoint(epoch(first.day()), first.open(), high, low, last.close(), volume));
        }
        return out;
    }

    static long epoch(final LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private static OffsetDateTime start(final LocalDate day) {
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static LocalDate bucket(final OffsetDateTime t, final Bucket bucket) {
        final LocalDate d = t.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        return bucket == Bucket.WEEK ? d.minusDays(d.getDayOfWeek().getValue() - 1L) : d;   // ISO week starts Monday
    }

    private static List<String> strings(final Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static double round(final double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static Double roundOrNull(final Double v) {
        return v == null ? null : round(v);
    }

    static long days(final LocalDate from, final LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }
}
