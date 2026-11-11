package data.provider.scheduled;

import data.provider.services.ArticleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ScrapeScheduler {

    private final ArticleService articleService;

    /**
     * Checks every few minutes (SCRAPE_TICK_MS, default 5 min) which sources
     * are due according to their own scrapeIntervalMinutes. The tick only
     * bounds scheduling precision; the per-source interval sets frequency.
     */
    @Scheduled(fixedDelayString = "${scrape.tick-ms:300000}", initialDelayString = "${scrape.initial-delay-ms:60000}")
    public void triggerScheduledScrape() {
        articleService.triggerDueScrapes();
    }
}
