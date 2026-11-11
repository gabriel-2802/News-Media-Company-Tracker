package data.provider;

import data.provider.dto.requests.AttachArticleRequest;
import data.provider.dto.requests.EnrichmentRequest;
import data.provider.dto.requests.EnrichmentRequest.Mention;
import data.provider.services.CompanyService;
import data.provider.services.StoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the live-run failure: the clusterer attaching articles to a story
 * while the enricher enriches the same articles. Both write the story and its
 * AFFECTS events, which deadlocked in Neo4j and surfaced as HTTP 500. Every
 * write must now succeed (transient errors are retried in a fresh
 * transaction) and the story counters must not be double-counted by retries.
 */
@SpringBootTest
class ConcurrentWritesIntegrationTests {

    private static final int ARTICLES = 24;

    @Autowired private Neo4jClient neo4j;
    @Autowired private StoryService storyService;
    @Autowired private CompanyService companyService;

    private final String run = "it-" + UUID.randomUUID();
    private final String company = run + "-bank";

    private String url(int i) {
        return "https://example.test/" + run + "/" + i;
    }

    @BeforeEach
    void seed() {
        neo4j.query("""
                CREATE (src:NewsSource {id: randomUUID(), name: $run, category: 'TRADE_PRESS',
                                        baseUrl: 'https://example.test', rssUrl: 'https://example.test/' + $run})
                CREATE (:Story {id: $run, title: 'story', articleCount: 0, sourceCount: 0, trendingScore: 0.0,
                                createdAt: datetime(), lastUpdated: datetime()})
                CREATE (:Company {id: $company, name: $run + ' Bank', kind: 'BANK'})
                WITH src
                UNWIND range(0, $n - 1) AS i
                CREATE (src)-[:PUBLISHED]->(:Article {id: randomUUID(), url: 'https://example.test/' + $run + '/' + i,
                                                     title: 'Bank news ' + i, publishedAt: localdatetime()})
                """).bindAll(Map.of("run", run, "company", company, "n", ARTICLES)).run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("""
                MATCH (n) WHERE n.name STARTS WITH $run OR n.url STARTS WITH 'https://example.test/' + $run
                       OR n.id IN [$run, $company]
                DETACH DELETE n
                """).bindAll(Map.of("run", run, "company", company)).run();
    }

    @Test
    void concurrentAttachAndEnrichmentOnOneStoryAllSucceedWithoutDoubleCounting() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        IntStream.range(0, ARTICLES).forEach(i -> {
            tasks.add(() -> storyService.attachArticle(run, new AttachArticleRequest(url(i))));
            tasks.add(() -> companyService.setEnrichment(new EnrichmentRequest(url(i), "negative", -0.5,
                    List.of(new Mention(company, 1, 0.8, true, -0.5)))));
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get();                       // throws if any write failed
            }
        } finally {
            pool.shutdown();
        }

        Map<String, Object> state = neo4j.query("""
                MATCH (s:Story {id: $run})
                OPTIONAL MATCH (s)-[r:AFFECTS]->(:Company {id: $company})
                RETURN s.articleCount AS articleCount, size([(s)<-[:BELONGS_TO]-(a:Article) | a]) AS members,
                       r.articleCount AS mentioning
                """).bindAll(Map.of("run", run, "company", company)).fetch().one().orElseThrow();

        assertThat(state).containsEntry("members", (long) ARTICLES);
        assertThat(state).containsEntry("articleCount", (long) ARTICLES);   // no double counting from retries
        assertThat(state).containsEntry("mentioning", (long) ARTICLES);     // event reflects every enriched member
    }
}
