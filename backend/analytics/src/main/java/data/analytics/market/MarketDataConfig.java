package data.analytics.market;

import data.analytics.config.AnalyticsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.HashSet;

@Configuration
@Slf4j
public class MarketDataConfig {

    @Bean
    public MarketDataProvider marketDataProvider(final AnalyticsProperties props, final RestClient.Builder builder) {
        final AnalyticsProperties.MarketData md = props.marketData();
        if ("twelvedata".equalsIgnoreCase(md.provider())) {
            final AnalyticsProperties.TwelveData td = md.twelvedata();
            if (td.apiKey() == null || td.apiKey().isBlank()) {
                log.warn("MARKET_DATA_PROVIDER=twelvedata but TWELVEDATA_API_KEY is empty: no prices will be fetched");
                return new NoopMarketDataProvider();
            }
            log.info("Market data: Twelve Data, exchanges {}", td.exchanges());
            return new TwelveDataProvider(builder.clone().baseUrl(td.baseUrl()).build(), td.apiKey(), new HashSet<>(td.exchanges()));
        }
        log.warn("Market data provider '{}': no prices will be fetched (set MARKET_DATA_PROVIDER=twelvedata and TWELVEDATA_API_KEY)", md.provider());
        return new NoopMarketDataProvider();
    }
}
