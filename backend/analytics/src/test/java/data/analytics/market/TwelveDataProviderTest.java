package data.analytics.market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Response shapes from the Twelve Data time_series documentation. */
class TwelveDataProviderTest {

    private MockRestServiceServer server;
    private TwelveDataProvider provider;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.example.test");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TwelveDataProvider(builder.build(), "KEY", Set.of("XNYS"));
    }

    @Test
    void parsesStringNumbersOldestFirst() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example.test/time_series")))
                .andExpect(queryParam("symbol", "JPM"))
                .andExpect(queryParam("country", "United%20States"))   // US: by country, not mic_code (encoded once)
                .andExpect(queryParam("interval", "1day"))
                .andExpect(queryParam("apikey", "KEY"))
                .andRespond(withSuccess("""
                        {"meta": {"symbol": "JPM", "interval": "1day", "currency": "USD", "exchange": "NYSE"},
                         "values": [
                           {"datetime": "2026-11-09", "open": "300.10", "high": "305.00", "low": "299.50", "close": "304.20", "volume": "9123456"},
                           {"datetime": "2026-11-10", "open": "304.00", "high": "306.40", "low": "301.10", "close": "302.75", "volume": "8000001"}
                         ],
                         "status": "ok"}
                        """, MediaType.APPLICATION_JSON));

        final List<Bar> bars = provider.daily("JPM", "XNYS", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10));

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).day()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(bars.get(1).close()).isEqualTo(302.75);
        assertThat(bars.get(1).volume()).isEqualTo(8000001L);
    }

    @Test
    void noDataForTheRangeIsEmptyNotAnError() {
        // As observed live: HTTP 400 with the error in the JSON body.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example.test/time_series")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code": 400, "message": "No data is available on the specified dates. Try setting different start/end dates.", "status": "error"}
                        """));
        assertThat(provider.daily("JPM", "XNYS", LocalDate.now(), LocalDate.now())).isEmpty();
    }

    @Test
    void unknownSymbolHttp404IsRaisedWithTheApiMessage() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example.test/time_series")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code": 404, "message": "**symbol** or **figi** parameter is missing or invalid.", "status": "error"}
                        """));
        assertThatThrownBy(() -> provider.daily("NOPE", "XNYS", LocalDate.now(), LocalDate.now()))
                .isInstanceOf(MarketDataProvider.MarketDataException.class)
                .hasMessageContaining("404").hasMessageContaining("symbol");
    }

    @Test
    void nonUsListingsAreRequestedByMic() {
        final TwelveDataProvider eu = new TwelveDataProvider(
                RestClient.builder().baseUrl("https://api.example.test").build(), "KEY", Set.of("XLON"));
        final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.example.test");
        final MockRestServiceServer euServer = MockRestServiceServer.bindTo(builder).build();
        final TwelveDataProvider lse = new TwelveDataProvider(builder.build(), "KEY", Set.of("XLON"));
        euServer.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example.test/time_series")))
                .andExpect(queryParam("mic_code", "XLON"))
                .andRespond(withSuccess("""
                        {"meta": {"symbol": "BARC"}, "values": [], "status": "ok"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(lse.daily("BARC", "XLON", LocalDate.now(), LocalDate.now())).isEmpty();
        euServer.verify();
        assertThat(eu.supports("XLON")).isTrue();
    }

    @Test
    void apiErrorsAreRaised() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.example.test/time_series")))
                .andRespond(withSuccess("""
                        {"code": 429, "message": "You have run out of API credits for the current minute.", "status": "error"}
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.daily("JPM", "XNYS", LocalDate.now(), LocalDate.now()))
                .isInstanceOf(MarketDataProvider.MarketDataException.class)
                .hasMessageContaining("429");
    }

    @Test
    void coverageFollowsConfiguredExchanges() {
        assertThat(provider.supports("XNYS")).isTrue();
        assertThat(provider.supports("XLON")).isFalse();
    }
}
