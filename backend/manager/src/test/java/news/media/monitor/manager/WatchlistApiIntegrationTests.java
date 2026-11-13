package news.media.monitor.manager;

import com.jayway.jsonpath.JsonPath;
import news.media.monitor.manager.services.AnalyticsClient;
import news.media.monitor.manager.services.CompanyDirectory;
import news.media.monitor.manager.services.WatchlistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/users/me/watchlist through HTTP and security; provider and analytics are mocked. */
@SpringBootTest(properties = "rabbitmq.listeners-auto-startup=false")
class WatchlistApiIntegrationTests {

    @MockitoBean private CompanyDirectory companies;
    @MockitoBean private AnalyticsClient analytics;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String email;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = TestUsers.email();
        TestUsers.create(jdbc, email);
        when(companies.find(anyString())).thenReturn(Optional.empty());
        for (String s : List.of("JPM", "GS", "BRK.B")) {
            when(companies.find(s)).thenReturn(Optional.of(new CompanyDirectory.Ticker(s, "XNYS", "USD", "co-" + s, "Name " + s)));
        }
        when(analytics.quotes(anyList())).thenReturn(List.of());
        when(analytics.latestEvents(anyList())).thenReturn(List.of());
    }

    @AfterEach
    void cleanUp() {
        TestUsers.deleteAll(jdbc);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
        return b.with(authentication(new UsernamePasswordAuthenticationToken(email, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")))));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return as(b).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void addUpdateListAndRemove() throws Exception {
        mvc.perform(as(put("/api/users/me/watchlist/jpm")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("JPM"))
                .andExpect(jsonPath("$.name").value("Name JPM"))
                .andExpect(jsonPath("$.minImpact").value(0.5))
                .andExpect(jsonPath("$.topics").isEmpty());

        mvc.perform(json(put("/api/users/me/watchlist/JPM"), "{\"minImpact\":0.7,\"topics\":[\"earnings_results\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minImpact").value(0.7))
                .andExpect(jsonPath("$.topics[0]").value("earnings_results"));
        mvc.perform(json(put("/api/users/me/watchlist/JPM"), "{\"minImpact\":0.6}"))           // topics kept
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0]").value("earnings_results"));
        mvc.perform(as(put("/api/users/me/watchlist/BRK.B"))).andExpect(status().isCreated());

        var listing = new AnalyticsClient.Listing("JPM", "XNYS", "USD", "JPMorgan Chase", "co-JPM");
        when(analytics.quotes(anyList())).thenReturn(List.of(new AnalyticsClient.Quote(listing, 1_762_992_000L, 310.5, 305.0, 5.5, 1.8)));
        when(analytics.latestEvents(anyList())).thenReturn(List.of(new AnalyticsClient.LatestEvent("JPM", "XNYS", "s1", "co-JPM",
                "JPMorgan beats", 1_762_990_000L, 0.8, 0.4, 3, List.of("earnings_results"))));

        String body = mvc.perform(as(get("/api/users/me/watchlist"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].symbol")).containsExactly("JPM", "BRK.B");
        assertThat(JsonPath.<String>read(body, "$[0].name")).isEqualTo("JPMorgan Chase");
        assertThat(JsonPath.<Number>read(body, "$[0].quote.last").doubleValue()).isEqualTo(310.5);
        assertThat(JsonPath.<Number>read(body, "$[0].quote.changePct").doubleValue()).isEqualTo(1.8);
        assertThat(JsonPath.<String>read(body, "$[0].latestEvent.title")).isEqualTo("JPMorgan beats");
        assertThat(JsonPath.<Object>read(body, "$[1].quote")).isNull();
        assertThat(JsonPath.<Object>read(body, "$[1].latestEvent")).isNull();

        mvc.perform(as(delete("/api/users/me/watchlist/jpm"))).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/users/me/watchlist/JPM"))).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/users/me/watchlist"))).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void analyticsDownStillServesTheWatchlist() throws Exception {
        mvc.perform(as(put("/api/users/me/watchlist/GS"))).andExpect(status().isCreated());
        // AnalyticsClient swallows failures into empty lists; the mock models that outcome
        mvc.perform(as(get("/api/users/me/watchlist")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("GS"))
                .andExpect(jsonPath("$[0].name").value("Name GS"))
                .andExpect(jsonPath("$[0].quote").doesNotExist());
    }

    @Test
    void validationAndErrors() throws Exception {
        mvc.perform(put("/api/users/me/watchlist/JPM")).andExpect(status().isUnauthorized());
        mvc.perform(as(put("/api/users/me/watchlist/NOPE"))).andExpect(status().isNotFound());
        mvc.perform(as(put("/api/users/me/watchlist/J$M"))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/users/me/watchlist/JPM"), "{\"minImpact\":1.5}")).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/users/me/watchlist/JPM"), "{\"topics\":[\"Bad Topic\"]}")).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/users/me/watchlist/JPM"), "{not json")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/users/me/watchlist"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void limitOfFiftyTickers() throws Exception {
        when(companies.find(anyString())).thenAnswer(inv -> Optional.of(new CompanyDirectory.Ticker(
                CompanyDirectory.normalize(inv.getArgument(0)), "XNYS", "USD", "co", "n")));
        for (int i = 0; i < WatchlistService.MAX_TICKERS; i++) {
            mvc.perform(as(put("/api/users/me/watchlist/T" + i))).andExpect(status().isCreated());
        }
        mvc.perform(as(put("/api/users/me/watchlist/T999"))).andExpect(status().isConflict());
        mvc.perform(json(put("/api/users/me/watchlist/T0"), "{\"minImpact\":0.9}")).andExpect(status().isOk());   // updates still allowed
    }

    @Test
    void genericSubscriptionEndpointAcceptsTickers() throws Exception {
        mvc.perform(json(post("/api/users/me/subscriptions"), "{\"type\":\"TICKER\",\"targetId\":\"gs\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TICKER"))
                .andExpect(jsonPath("$.targetId").value("GS"))
                .andExpect(jsonPath("$.minImpact").value(0.5));
        mvc.perform(json(post("/api/users/me/subscriptions"), "{\"type\":\"TICKER\",\"targetId\":\"GS\"}"))
                .andExpect(status().isConflict());
        String id = JsonPath.read(mvc.perform(as(get("/api/users/me/subscriptions"))).andReturn().getResponse().getContentAsString(),
                "$.content[0].id");
        mvc.perform(as(get("/api/users/me/watchlist"))).andExpect(jsonPath("$[0].subscriptionId").value(id));
        mvc.perform(as(delete("/api/users/me/subscriptions/" + id))).andExpect(status().isNoContent());   // no provider call for tickers
        mvc.perform(as(get("/api/users/me/watchlist"))).andExpect(jsonPath("$.length()").value(0));
    }
}
