package data.provider.repositories;

import data.provider.models.NewsSource;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface NewsSourceRepository extends Neo4jRepository<NewsSource, String> {
    boolean existsByBaseUrl(String baseUrl);

    boolean existsByName(String name);

    Optional<NewsSource> findByName(String name);

    boolean existsByRssUrl(String rssUrl);

    @Query("""
            MATCH (n:NewsSource {name: $sourceName})
            SET n.failureCount = n.failureCount + 1,
                n.isDisabled = CASE WHEN n.failureCount + 1 >= 3 THEN true ELSE n.isDisabled END
            RETURN n
            """)
    Optional<NewsSource> incrementFailureCount(String sourceName);

    @Query("""
            MATCH (n:NewsSource {name: $sourceName})
            SET n.failureCount = 0,
                n.isDisabled = false
            RETURN n
            """)
    Optional<NewsSource> resetFailureCount(String sourceName);

    /**
     * Selects enabled sources whose scrape interval has elapsed and stamps
     * lastScrapeQueuedAt in the same write, so concurrent provider
     * instances never queue the same source twice in one interval.
     */
    @Query("""
            MATCH (n:NewsSource)
            WHERE coalesce(n.isDisabled, false) = false
              AND (n.lastScrapeQueuedAt IS NULL
                   OR n.lastScrapeQueuedAt + duration({minutes: coalesce(n.scrapeIntervalMinutes, 60)}) <= datetime())
            SET n.lastScrapeQueuedAt = datetime()
            RETURN n.name
            """)
    List<String> claimDueSources();
}
