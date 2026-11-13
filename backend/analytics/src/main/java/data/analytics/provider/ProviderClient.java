package data.analytics.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import data.analytics.config.AnalyticsProperties;
import data.analytics.events.EventMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls to the provider. Reads are public; the impact write-back needs a
 * ROLE_SYSTEM token, obtained from the manager with the system code (same
 * as the Python workers) and refreshed once on 401.
 */
@Component
@Slf4j
public class ProviderClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompanyListing(String id, String name, String kind, List<Ticker> tickers) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Ticker(String symbol, String exchange, String currency, boolean primary) {}
    }

    private final RestClient provider;
    private final RestClient manager;
    private final String systemCode;
    private volatile String token;

    public ProviderClient(final AnalyticsProperties props, final RestClient.Builder builder) {
        this.provider = builder.clone().baseUrl(props.providerUrl()).build();
        this.manager = builder.clone().baseUrl(props.managerUrl()).build();
        this.systemCode = props.systemCode();
    }

    public List<CompanyListing> companies() {
        return provider.get().uri("/api/companies").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public List<EventMessage> events(final String since, final int page, final int count) {
        return provider.get()
                .uri(b -> {
                    b.path("/api/events").queryParam("page", page).queryParam("count", count);
                    if (since != null) {
                        b.queryParam("since", since);
                    }
                    return b.build();
                })
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    /** PATCH the impact; returns false if the provider no longer has the event (400). */
    public boolean patchImpact(final String storyId, final String companyId, final Map<String, Object> body) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                provider.patch()
                        .uri("/api/events/{storyId}/{companyId}/impact", storyId, companyId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve().toBodilessEntity();
                return true;
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED && attempt == 0) {
                    token = null;                       // expired: log in again and retry once
                    continue;
                }
                if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                    log.info("Provider rejected impact for {}/{}: {}", storyId, companyId, e.getResponseBodyAsString());
                    return false;
                }
                throw e;
            }
        }
        return false;
    }

    private String token() {
        if (token == null) {
            final Map<String, Object> response = manager.post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("systemCode", systemCode))
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            token = response == null ? null : response.values().stream()
                    .filter(v -> v instanceof String s && s.chars().filter(ch -> ch == '.').count() == 2)
                    .map(String.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Manager login returned no token"));
        }
        return token;
    }
}
