package data.provider;

import data.provider.dto.requests.EnrichmentRequest;
import data.provider.dto.requests.EnrichmentRequest.Mention;
import data.provider.exceptions.BusinessException;
import data.provider.services.CompanyService;
import data.provider.services.EventService;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Enrichment (sentiment + MENTIONS) and the derived Story-[:AFFECTS]->Company
 * events, against the configured Neo4j. All nodes carry a run marker and are
 * deleted afterwards.
 *
 * Fixture: story S (sourceCount 2) with
 *   A1 (regulator source, published 2026-11-10 12:00, topic regulation 0.9)
 *   A2 (trade press,      published 2026-11-09 08:00)
 * and companies C1, C2 (banks) and REG (a regulator).
 */
@SpringBootTest
// Never publish to the shared broker: event.upserted from test fixtures would reach analytics/manager.
@MockitoBean(types = RabbitTemplate.class)
class EnrichmentEventIntegrationTests {

    @Autowired private Neo4jClient neo4j;
    @Autowired private CompanyService companyService;
    @Autowired private EventService eventService;

    private final String run = "it-" + UUID.randomUUID();
    private final String a1 = "https://example.test/" + run + "/a1";
    private final String a2 = "https://example.test/" + run + "/a2";
    private final String loose = "https://example.test/" + run + "/unclustered";
    private final String c1 = run + "-c1";
    private final String c2 = run + "-c2";
    private final String reg = run + "-reg-co";

    @BeforeEach
    void seed() {
        neo4j.query("""
                CREATE (reg:NewsSource {id: randomUUID(), name: $run + '-reg', category: 'REGULATOR',
                                        baseUrl: 'https://example.test', rssUrl: 'https://example.test/' + $run + '/reg'})
                CREATE (trade:NewsSource {id: randomUUID(), name: $run + '-trade', category: 'TRADE_PRESS',
                                          baseUrl: 'https://example.test', rssUrl: 'https://example.test/' + $run + '/trade'})
                CREATE (s:Story {id: $run, title: 'story', sourceCount: 2, articleCount: 2})
                CREATE (x1:Article {id: randomUUID(), url: $a1, title: 'Regulator fines Bank One', publishedAt: localdatetime('2026-11-10T12:00:00')})
                CREATE (x2:Article {id: randomUUID(), url: $a2, title: 'Bank One under scrutiny', publishedAt: localdatetime('2026-11-09T08:00:00')})
                CREATE (x3:Article {id: randomUUID(), url: $loose, title: 'Not clustered yet', publishedAt: localdatetime()})
                CREATE (reg)-[:PUBLISHED]->(x1), (trade)-[:PUBLISHED]->(x2), (trade)-[:PUBLISHED]->(x3)
                CREATE (x1)-[:BELONGS_TO]->(s), (x2)-[:BELONGS_TO]->(s)
                MERGE (t:Topic {name: 'regulation_legal_enforcement'}) ON CREATE SET t.id = randomUUID()
                CREATE (x1)-[:HAS_TOPIC {score: 0.9}]->(t)
                CREATE (:Company {id: $c1, name: $run + ' Bank One', kind: 'BANK'})
                CREATE (:Company {id: $c2, name: $run + ' Bank Two', kind: 'BANK'})
                CREATE (:Company {id: $reg, name: $run + ' Regulator', kind: 'REGULATOR'})
                """).bindAll(Map.of("run", run, "a1", a1, "a2", a2, "loose", loose, "c1", c1, "c2", c2, "reg", reg)).run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("""
                MATCH (n) WHERE n.name STARTS WITH $run OR n.url STARTS WITH 'https://example.test/' + $run
                       OR n.id IN [$run, $c1, $c2, $reg]
                DETACH DELETE n
                """).bindAll(Map.of("run", run, "c1", c1, "c2", c2, "reg", reg)).run();
    }

    private void enrich(String url, double sentiment, Mention... mentions) {
        companyService.setEnrichment(new EnrichmentRequest(url, sentiment < 0 ? "negative" : "positive", sentiment, List.of(mentions)));
    }

    private Optional<Map<String, Object>> event(String companyId) {
        return neo4j.query("""
                MATCH (:Story {id: $run})-[r:AFFECTS]->(:Company {id: $cid})
                RETURN r.impactScore AS impact, r.articleCount AS articles, r.sentiment AS sentiment,
                       toString(r.firstSeen) AS firstSeen
                """).bindAll(Map.of("run", run, "cid", companyId)).fetch().one();
    }

    @Test
    void enrichmentStoresSentimentAndMentionsAndCreatesEvent() {
        enrich(a1, -0.6, new Mention(c1, 3, 0.9, true, -0.6));

        Map<String, Object> article = neo4j.query("""
                MATCH (a:Article {url: $url})-[m:MENTIONS]->(c:Company)
                RETURN a.sentimentLabel AS label, a.sentimentScore AS score, c.id AS company, m.count AS count, m.inTitle AS inTitle
                """).bind(a1).to("url").fetch().one().orElseThrow();
        assertThat(article).containsEntry("label", "negative").containsEntry("score", -0.6)
                .containsEntry("company", c1).containsEntry("count", 3L).containsEntry("inTitle", true);

        assertThat(event(c1)).isPresent();
        assertThat(event(c1).get()).containsEntry("articles", 1L);
    }

    @Test
    void eventAggregatesStoryArticlesWithDocumentedImpactFormula() {
        enrich(a1, -0.6, new Mention(c1, 3, 0.9, true, -0.6));
        enrich(a2, -0.2, new Mention(c1, 1, 0.5, false, -0.2), new Mention(c2, 1, 0.3, false, null));

        Map<String, Object> e = event(c1).orElseThrow();
        // 0.25*breadth(2/5) + 0.15*coverage(2/3) + 0.20*salience(0.9) + 0.25*topic(1.0*0.9)
        //   + 0.15*|sentiment|(mean(-0.6,-0.2)=0.4) + 0.10 official = 0.765
        assertThat((Double) e.get("impact")).isCloseTo(0.765, within(0.001));
        assertThat(e).containsEntry("articles", 2L);
        assertThat((Double) e.get("sentiment")).isCloseTo(-0.4, within(0.001));
        assertThat((String) e.get("firstSeen")).startsWith("2026-11-09T08:00");   // earliest article
        assertThat(event(c2)).isPresent();
    }

    @Test
    void reEnrichmentReplacesMentionsAndDropsStaleEvents() {
        enrich(a2, -0.2, new Mention(c1, 1, 0.5, false, -0.2), new Mention(c2, 1, 0.3, false, null));
        assertThat(event(c2)).isPresent();

        enrich(a2, -0.2, new Mention(c1, 1, 0.5, false, -0.2));

        long mentionsOfC2 = neo4j.query("MATCH (:Article {url: $url})-[m:MENTIONS]->(:Company {id: $cid}) RETURN count(m)")
                .bindAll(Map.of("url", a2, "cid", c2)).fetchAs(Long.class).one().orElseThrow();
        assertThat(mentionsOfC2).isZero();
        assertThat(event(c2)).isEmpty();
        assertThat(event(c1)).isPresent();
    }

    @Test
    void regulatorsKeepMentionsButGetNoEvents() {
        // A pre-existing regulator event (created before this rule) must be removed on the next refresh.
        neo4j.query("MATCH (s:Story {id: $run}), (c:Company {id: $reg}) CREATE (s)-[:AFFECTS {impactScore: 0.7}]->(c)")
                .bindAll(Map.of("run", run, "reg", reg)).run();

        enrich(a1, -0.6, new Mention(c1, 2, 0.9, true, -0.6), new Mention(reg, 1, 0.7, true, -0.6));

        long regMentions = neo4j.query("MATCH (:Article {url: $url})-[m:MENTIONS]->(:Company {id: $cid}) RETURN count(m)")
                .bindAll(Map.of("url", a1, "cid", reg)).fetchAs(Long.class).one().orElseThrow();
        assertThat(regMentions).isEqualTo(1L);
        assertThat(event(reg)).isEmpty();
        assertThat(event(c1)).isPresent();
    }

    @Test
    void unknownCompanyIsRejectedAndNothingIsWritten() {
        assertThatThrownBy(() -> enrich(a1, 0.3, new Mention(run + "-missing", 1, 0.5, false, 0.3)))
                .isInstanceOf(BusinessException.class);

        Object sentiment = neo4j.query("MATCH (a:Article {url: $url}) RETURN a.sentimentScore")
                .bind(a1).to("url").fetch().one().orElseThrow().values().iterator().next();
        assertThat(sentiment).isNull();
    }

    @Test
    void unclusteredArticleGetsMentionsButNoEventUntilItJoinsAStory() {
        enrich(loose, 0.5, new Mention(c2, 2, 0.8, true, 0.5));
        assertThat(event(c2)).isEmpty();

        neo4j.query("MATCH (a:Article {url: $url}), (s:Story {id: $run}) CREATE (a)-[:BELONGS_TO]->(s)")
                .bindAll(Map.of("url", loose, "run", run)).run();
        assertThat(eventService.refreshForArticle(loose)).isEqualTo(1L);
        assertThat(event(c2)).isPresent();
    }
}
