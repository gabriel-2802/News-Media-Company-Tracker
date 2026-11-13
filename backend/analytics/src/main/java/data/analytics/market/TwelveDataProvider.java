package data.analytics.market;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Twelve Data time_series API (https://twelvedata.com/docs#time-series):
 * GET /time_series?symbol=&interval=1day&start_date=&end_date=&order=ASC&apikey=
 * Success: {"meta": {...}, "values": [{"datetime","open","high","low","close","volume"}], "status": "ok"}
 * Error:   {"code": 4xx, "message": "...", "status": "error"}, sent with that HTTP status
 *          (verified live; the docs show errors inside HTTP 200).
 * Numbers arrive as strings. The free plan covers US listings only.
 *
 * US listings are requested by symbol + country, not mic_code: Twelve Data
 * files Nasdaq stocks under the tier MICs (XNGS/XNMS/XNCM) and rejects XNAS,
 * and the SEC and Twelve Data disagree on the exchange of some banks (FITB:
 * NYSE per SEC, Nasdaq per Twelve Data). Non-US listings use mic_code.
 */
public class TwelveDataProvider implements MarketDataProvider {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String status, Integer code, String message, List<Value> values) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Value(String datetime, String open, String high, String low, String close, String volume) {}

    private final RestClient http;
    private final String apiKey;
    private final Set<String> exchanges;

    /** MICs of US venues, looked up by country instead of exchange. */
    static final Set<String> US_MICS = Set.of("XNYS", "XNAS", "XNGS", "XNMS", "XNCM", "ARCX", "BATS", "XASE");

    public TwelveDataProvider(final RestClient http, final String apiKey, final Set<String> exchanges) {
        this.http = http;
        this.apiKey = apiKey;
        this.exchanges = exchanges;
    }

    @Override
    public String name() {
        return "twelvedata";
    }

    @Override
    public boolean supports(final String exchange) {
        return exchanges.contains(exchange);
    }

    @Override
    public List<Bar> daily(final String symbol, final String exchange, final LocalDate from, final LocalDate to) {
        final Response response = http.get()
                .uri(b -> {
                    b.path("/time_series").queryParam("symbol", symbol);
                    if (US_MICS.contains(exchange)) {
                        b.queryParam("country", "United States");
                    } else {
                        b.queryParam("mic_code", exchange);
                    }
                    return b.queryParam("interval", "1day")
                        .queryParam("start_date", from.toString())
                        .queryParam("end_date", to.toString())
                        .queryParam("order", "ASC")
                        .queryParam("outputsize", 5000)
                        .queryParam("apikey", apiKey)
                        .build();
                })
                .retrieve()
                // Error details come in the JSON body of a 4xx response: read it instead of throwing.
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { })
                .body(Response.class);
        if (response == null) {
            throw new MarketDataException("Empty response for " + symbol + "@" + exchange);
        }
        if ("error".equals(response.status())) {
            // 400 "No data is available on the specified dates" is not a failure: there is just nothing new.
            if (response.message() != null && response.message().toLowerCase().contains("no data")) {
                return List.of();
            }
            throw new MarketDataException("Twelve Data " + response.code() + " for " + symbol + "@" + exchange + ": " + response.message());
        }
        if (response.values() == null) {
            return List.of();
        }
        return response.values().stream()
                .map(v -> new Bar(LocalDate.parse(v.datetime().substring(0, 10)), num(v.open()), num(v.high()),
                        num(v.low()), num(v.close()), v.volume() == null ? null : (long) Double.parseDouble(v.volume())))
                .toList();
    }

    private static Double num(final String s) {
        return s == null || s.isBlank() ? null : Double.valueOf(s);
    }
}
