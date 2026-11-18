package data.analytics.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs (price ingest, event studies) run unless app.scheduling.enabled=false. The
 * integration tests turn them off (src/test/resources/config/application.properties):
 * they start the application against the shared local databases with the
 * provider mocked, and a job firing there would store event studies for real events and "write them back" to the mock, so the real provider never gets them.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
