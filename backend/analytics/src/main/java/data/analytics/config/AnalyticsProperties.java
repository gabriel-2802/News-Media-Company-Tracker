package data.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "analytics")
public record AnalyticsProperties(
        String providerUrl,
        String managerUrl,
        String systemCode,
        String eventsQueue,
        MarketData marketData,
        String ingestCron,
        long impactIntervalMs,
        Map<String, String> benchmarks,       // exchange MIC -> "SYMBOL@MIC"
        Study study) {

    public record MarketData(String provider, int historyDays, long requestDelayMs, TwelveData twelvedata) {}

    public record TwelveData(String baseUrl, String apiKey, List<String> exchanges) {}

    public record Study(int estimationStart, int estimationEnd, int minEstimationDays) {}
}
