package data.provider;

import data.provider.dto.messages.EventMessage;
import data.provider.dto.requests.EnrichmentRequest;
import data.provider.dto.requests.EnrichmentRequest.Mention;
import data.provider.dto.requests.ImpactRequest;
import data.provider.exceptions.BusinessException;
import data.provider.services.CompanyService;
import data.provider.services.EventQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** event.upserted publishing, the events feed and the impact write-back. */
@SpringBootTest
class EventPublishingIntegrationTests {

    @MockitoBean private RabbitTemplate rabbitTemplate;
    @Autowired private Neo4jClient neo4j;
    @Autowired private CompanyService companyService;
    @Autowired private EventQueryService eventQueryService;

    private final String run = "it-" + UUID.randomUUID();
    private final String url = "https://example.test/" + run + "/a";
    private final String bank = run + "-bank";
    private final String other = run + "-other";

    @BeforeEach
    void seed() {
        neo4j.query("""
                CREATE (src:NewsSource {id: randomUUID(), name: $run, category: 'TRADE_PRESS',
                                        baseUrl: 'https://example.test', rssUrl: 'https://example.test/' + $run})
                CREATE (s:Story {id: $run, title: 'Bank fined', articleCount: 1, sourceCount: 1, trendingScore: 0.0})
                CREATE (a:Article {id: randomUUID(), url: $url, title: 'Bank fined', publishedAt: localdatetime('2026-11-10T09:00:00')})
                CREATE (src)-[:PUBLISHED]->(a)-[:BELONGS_TO]->(s)
                MERGE (t:Topic {name: 'regulation_legal_enforcement'}) ON CREATE SET t.id = randomUUID()
                CREATE (a)-[:HAS_TOPIC {score: 0.9}]->(t)
                CREATE (c:Company {id: $bank, name: $run + ' Bank', kind: 'BANK'})
                CREATE (:Company {id: $other, name: $run + ' Other', kind: 'BANK'})
                CREATE (c)-[:LISTED_AS {primary: true}]->(:Ticker {id: randomUUID(), symbol: 'ITBK', exchange: $run, currency: 'USD'})
                """).bindAll(Map.of("run", run, "url", url, "bank", bank, "other", other)).run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("""
                MATCH (n) WHERE n.name STARTS WITH $run OR n.url STARTS WITH 'https://example.test/' + $run
                       OR n.id IN [$run, $bank, $other] OR n.exchange = $run
                DETACH DELETE n
                """).bindAll(Map.of("run", run, "bank", bank, "other", other)).run();
    }

    private List<EventMessage> published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, org.mockito.Mockito.atLeast(0)).convertAndSend(eq("news_monitor"), eq("event.upserted"), captor.capture());
        return captor.getAllValues().stream().map(EventMessage.class::cast).toList();
    }

    @Test
    void enrichmentPublishesEventWithTickerTopicsAndVersion() {
        companyService.setEnrichment(new EnrichmentRequest(url, "negative", -0.6, List.of(new Mention(bank, 2, 0.9, true, -0.6))));

        List<EventMessage> messages = published();
        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(m.storyId()).isEqualTo(run);
            assertThat(m.companyId()).isEqualTo(bank);
            assertThat(m.symbol()).isEqualTo("ITBK");
            assertThat(m.currency()).isEqualTo("USD");
            assertThat(m.topics()).containsExactly("regulation_legal_enforcement");
            assertThat(m.firstSeen()).startsWith("2026-11-10T09:00");
            assertThat(m.deleted()).isFalse();
            assertThat(m.version()).isPositive();
        });
    }

    @Test
    void removedEventIsPublishedAsDeleted() {
        companyService.setEnrichment(new EnrichmentRequest(url, "negative", -0.6, List.of(new Mention(bank, 1, 0.9, true, -0.6))));
        clearInvocations(rabbitTemplate);

        companyService.setEnrichment(new EnrichmentRequest(url, "negative", -0.6, List.of(new Mention(other, 1, 0.9, true, -0.6))));

        List<EventMessage> messages = published();
        assertThat(messages).anySatisfy(m -> {
            assertThat(m.companyId()).isEqualTo(bank);
            assertThat(m.deleted()).isTrue();
        });
        assertThat(messages).anySatisfy(m -> {
            assertThat(m.companyId()).isEqualTo(other);
            assertThat(m.deleted()).isFalse();
        });
    }

    @Test
    void nothingIsPublishedWhenTheWriteRollsBack() {
        assertThatThrownBy(() -> companyService.setEnrichment(new EnrichmentRequest(url, "negative", -0.6,
                List.of(new Mention(run + "-missing", 1, 0.9, true, -0.6))))).isInstanceOf(BusinessException.class);
        verify(rabbitTemplate, never()).convertAndSend(eq("news_monitor"), eq("event.upserted"), any(Object.class));
    }

    @Test
    void eventsFeedAndImpactWriteBack() {
        companyService.setEnrichment(new EnrichmentRequest(url, "negative", -0.6, List.of(new Mention(bank, 1, 0.9, true, -0.6))));

        List<EventMessage> feed = eventQueryService.eventsSince("2000-01-01T00:00:00Z", 0, 1000).stream()
                .filter(e -> run.equals(e.storyId())).toList();
        assertThat(feed).singleElement().satisfies(e -> assertThat(e.symbol()).isEqualTo("ITBK"));

        eventQueryService.setImpact(run, bank, new ImpactRequest("KBE", -0.018, -0.031, null, 1.12, 0.04, 220));
        Map<String, Object> stored = neo4j.query("""
                MATCH (:Story {id: $run})-[r:AFFECTS]->(:Company {id: $bank})
                RETURN r.car1 AS car1, r.benchmark AS benchmark, r.estimationDays AS days
                """).bindAll(Map.of("run", run, "bank", bank)).fetch().one().orElseThrow();
        assertThat(stored).containsEntry("car1", -0.031).containsEntry("benchmark", "KBE").containsEntry("days", 220L);
        verify(rabbitTemplate, times(1)).convertAndSend(eq("news_monitor"), eq("event.upserted"), any(Object.class));  // impact write does not re-publish

        assertThatThrownBy(() -> eventQueryService.setImpact(run, other, new ImpactRequest("KBE", 0.0, 0.0, null, 1.0, 0.5, 220)))
                .isInstanceOf(BusinessException.class);
    }
}
