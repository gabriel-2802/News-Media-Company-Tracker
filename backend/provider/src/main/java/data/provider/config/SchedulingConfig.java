package data.provider.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs (the due-source scrape scheduler) run unless app.scheduling.enabled=false. The
 * integration tests turn them off (src/test/resources/config/application.properties):
 * they start the application against the shared local databases with the
 * broker mocked, and a job firing there would mark real sources as queued while no scrape job is sent, so they skip their next scrape.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
