package data.provider;

import data.provider.dto.requests.ArticleRequest;
import data.provider.dto.requests.EnrichmentRequest;
import data.provider.dto.requests.EnrichmentRequest.Mention;
import data.provider.dto.requests.TopicsSetRequest;
import data.provider.dto.requests.TopicsSetRequest.TopicScore;
import data.provider.services.ArticleService;
import data.provider.services.CompanyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.LocalDateTime;
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
 * Every saved article gets its own story, and the enricher and classifier then
 * write that story's AFFECTS events in parallel, many stories at once naming
 * the same company. Every write must succeed (transient Neo4j errors such as
 * deadlocks are retried in a fresh transaction) and each article must end up
 * with exactly one story carrying one event.
 */
@SpringBootTest
// Never publish to the shared broker: event.upserted from test fixtures would reach analytics/manager.
@MockitoBean(types = RabbitTemplate.class)
class ConcurrentWritesIntegrationTests {

    private static final int ARTICLES = 24;

    @Autowired private Neo4jClient neo4j;
    @Autowired private ArticleService articleService;
    @Autowired private CompanyService companyService;

    private final String run = "it-" + UUID.randomUUID();
    private final String company = run + "-bank";

    private String url(int i) {
        return "https://example.test/" + run + "/" + i;
    }

    @BeforeEach
    void seed() {
        neo4j.query("""
                CREATE (:NewsSource {id: randomUUID(), name: $run, category: 'TRADE_PRESS',
                                     baseUrl: 'https://example.test', rssUrl: 'https://example.test/' + $run})
                CREATE (:Company {id: $company, name: $run + ' Bank', kind: 'BANK'})
                MERGE (t:Topic {name: 'regulation_legal_enforcement'}) ON CREATE SET t.id = randomUUID()
                """).bindAll(Map.of("run", run, "company", company)).run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("""
                MATCH (a:Article) WHERE a.url STARTS WITH 'https://example.test/' + $run
                OPTIONAL MATCH (a)-[:BELONGS_TO]->(s:Story)
                DETACH DELETE a, s
                """).bind(run).to("run").run();
        neo4j.query("MATCH (n) WHERE n.name STARTS WITH $run OR n.id = $company DETACH DELETE n")
                .bindAll(Map.of("run", run, "company", company)).run();
    }

    @Test
    void savingGivesEachArticleItsOwnStory() {
        articleService.saveArticle(new ArticleRequest("a", "Bank news", url(0), "body", LocalDateTime.now(), run));

        Map<String, Object> story = neo4j.query("""
                MATCH (a:Article {url: $url})-[:BELONGS_TO]->(s:Story)
                RETURN count(s) AS stories, s.title AS title, s.articleCount AS articles, s.sourceCount AS sources
                """).bind(url(0)).to("url").fetch().one().orElseThrow();
        assertThat(story).containsEntry("stories", 1L).containsEntry("title", "Bank news")
                .containsEntry("articles", 1L).containsEntry("sources", 1L);
    }

    @Test
    void concurrentEnrichmentAndTopicWritesAllSucceed() throws Exception {
        IntStream.range(0, ARTICLES).forEach(i -> articleService.saveArticle(
                new ArticleRequest("a", "Bank news " + i, url(i), "body", LocalDateTime.now(), run)));

        List<Callable<Object>> tasks = new ArrayList<>();
        IntStream.range(0, ARTICLES).forEach(i -> {
            tasks.add(() -> companyService.setEnrichment(new EnrichmentRequest(url(i), "negative", -0.5,
                    List.of(new Mention(company, 1, 0.8, true, -0.5)))));
            tasks.add(() -> articleService.setTopics(new TopicsSetRequest(url(i),
                    List.of(new TopicScore("regulation_legal_enforcement", 0.9)))));
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
                MATCH (a:Article) WHERE a.url STARTS WITH 'https://example.test/' + $run
                OPTIONAL MATCH (a)-[:BELONGS_TO]->(s:Story)
                OPTIONAL MATCH (s)-[r:AFFECTS]->(:Company {id: $company})
                RETURN count(DISTINCT a) AS articles, count(DISTINCT s) AS stories, count(r) AS events,
                       min(r.articleCount) AS minMentioning, max(r.articleCount) AS maxMentioning
                """).bindAll(Map.of("run", run, "company", company)).fetch().one().orElseThrow();

        assertThat(state).containsEntry("articles", (long) ARTICLES)
                .containsEntry("stories", (long) ARTICLES)          // one story per article
                .containsEntry("events", (long) ARTICLES)           // one event per story
                .containsEntry("minMentioning", 1L).containsEntry("maxMentioning", 1L);
    }
}
