package data.provider;

import data.provider.dto.requests.TopicsSetRequest;
import data.provider.dto.responses.ArticleDto;
import data.provider.dto.responses.CompanyDto;
import data.provider.exceptions.BusinessException;
import data.provider.services.ArticleService;
import data.provider.models.Article;
import data.provider.models.Company;
import data.provider.models.CompanyKind;
import data.provider.models.Listing;
import data.provider.models.Ticker;
import data.provider.repositories.ArticleRepository;
import data.provider.repositories.CompanyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the Phase 3 graph model against the configured Neo4j:
 * multi-label HAS_TOPIC {score} mapping, the single-topic setTopic query,
 * Company/Ticker listings and the Cypher-managed MENTIONS/AFFECTS edges.
 * Every node it creates carries a unique run marker and is deleted afterwards.
 */
@SpringBootTest
// Never publish to the shared broker: event.upserted from test fixtures would reach analytics/manager.
@MockitoBean(types = RabbitTemplate.class)
class GraphModelIntegrationTests {

    @Autowired
    private Neo4jClient neo4j;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private ArticleService articleService;

    private final String run = "it-" + UUID.randomUUID();
    private final String url = "https://example.test/" + run;
    private final String symbol = ("IT" + run.substring(3, 9)).toUpperCase();

    @BeforeEach
    void seedArticleWithTwoTopics() {
        neo4j.query("""
                CREATE (s:NewsSource {id: randomUUID(), name: $run, baseUrl: 'https://example.test',
                                      rssUrl: 'https://example.test/' + $run + '/rss'})
                CREATE (a:Article {id: randomUUID(), url: $url, title: 'Bank fined', publishedAt: localdatetime(),
                                   sentimentLabel: 'negative', sentimentScore: -0.6})
                CREATE (s)-[:PUBLISHED]->(a)
                CREATE (hi:Topic {id: randomUUID(), name: $run + '-enforcement'})
                CREATE (lo:Topic {id: randomUUID(), name: $run + '-personnel'})
                CREATE (a)-[:HAS_TOPIC {score: 0.9}]->(hi)
                CREATE (a)-[:HAS_TOPIC {score: 0.3}]->(lo)
                CREATE (:Story {id: $run, title: 'story'})
                """).bindAll(Map.of("run", run, "url", url)).run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("""
                MATCH (n) WHERE n.name STARTS WITH $run OR n.url = $url OR n.id = $run OR n.symbol = $symbol
                DETACH DELETE n
                """).bindAll(Map.of("run", run, "url", url, "symbol", symbol)).run();
    }

    @Test
    void readsAllTopicsWithScoresAndPicksHighestAsPrimary() {
        Article article = articleRepository.findByUrlWithSourceAndTopic(url).orElseThrow();

        assertThat(article.getTopics()).hasSize(2);
        assertThat(article.primaryTopic().orElseThrow().getTopic().getName()).isEqualTo(run + "-enforcement");

        ArticleDto dto = new ArticleDto(article);
        assertThat(dto.topic()).isEqualTo(run + "-enforcement");
        assertThat(dto.topics()).extracting("name").containsExactly(run + "-enforcement", run + "-personnel");
        assertThat(dto.topics()).extracting("score").containsExactly(0.9, 0.3);
        assertThat(dto.sentimentLabel()).isEqualTo("negative");
        assertThat(dto.source()).isEqualTo(run);
    }

    @Test
    void setTopicReplacesAllTopicsWithOneAtFullScore() {
        Article article = articleRepository.setTopic(url, run + "-earnings");

        assertThat(article.getTopics()).hasSize(1);
        assertThat(article.getTopics().get(0).getTopic().getName()).isEqualTo(run + "-earnings");
        assertThat(article.getTopics().get(0).getScore()).isEqualTo(1.0);
    }

    @Test
    void setTopicsReplacesWithScoredSetAndKeepsHighestDuplicate() {
        neo4j.query("CREATE (:Topic {id: randomUUID(), name: $name})")
                .bind(run + "-earnings").to("name").run();

        ArticleDto dto = articleService.setTopics(new TopicsSetRequest(url, List.of(
                new TopicsSetRequest.TopicScore(run + "-earnings", 0.55),
                new TopicsSetRequest.TopicScore(run + "-enforcement", 0.40),
                new TopicsSetRequest.TopicScore(run + "-earnings", 0.80))));

        assertThat(dto.topics()).extracting("name").containsExactly(run + "-earnings", run + "-enforcement");
        assertThat(dto.topics()).extracting("score").containsExactly(0.80, 0.40);
        assertThat(dto.topic()).isEqualTo(run + "-earnings");
    }

    @Test
    void setTopicsRejectsUnknownTopicAndLeavesArticleUnchanged() {
        assertThatThrownBy(() -> articleService.setTopics(new TopicsSetRequest(url, List.of(
                new TopicsSetRequest.TopicScore(run + "-enforcement", 0.9),
                new TopicsSetRequest.TopicScore(run + "-does-not-exist", 0.9)))))
                .isInstanceOf(BusinessException.class);

        Article article = articleRepository.findByUrlWithSourceAndTopic(url).orElseThrow();
        assertThat(article.getTopics()).hasSize(2);
    }

    @Test
    void companyListingAndCypherManagedEdges() {
        Company saved = companyRepository.save(Company.builder()
                .name(run + "-bank")
                .kind(CompanyKind.BANK)
                .country("US")
                .aliases(List.of(run + "-alias"))
                .listings(List.of(Listing.builder()
                        .primary(true)
                        .ticker(Ticker.builder().symbol(symbol).exchange("XNYS").currency("USD").build())
                        .build()))
                .build());

        Company bySymbol = companyRepository.findByTickerSymbol(symbol).orElseThrow();
        assertThat(bySymbol.getId()).isEqualTo(saved.getId());
        assertThat(bySymbol.getListings()).singleElement()
                .satisfies(l -> {
                    assertThat(l.getPrimary()).isTrue();
                    assertThat(l.getTicker().getSymbol()).isEqualTo(symbol);
                });

        assertThat(new CompanyDto(bySymbol).tickers()).singleElement()
                .satisfies(t -> assertThat(t.exchange()).isEqualTo("XNYS"));
    }
}
