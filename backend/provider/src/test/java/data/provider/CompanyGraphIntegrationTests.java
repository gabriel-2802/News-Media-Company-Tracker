package data.provider;

import data.provider.dto.responses.GraphDto;
import data.provider.exceptions.BusinessException;
import data.provider.services.CompanyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GET /api/companies/{symbol}/graph. Fixture: bank A (ticker ITGA) and bank B;
 * a recent story affecting both (with CAR), an old story affecting only A.
 */
@SpringBootTest
class CompanyGraphIntegrationTests {

    @Autowired private Neo4jClient neo4j;
    @Autowired private CompanyService companyService;

    private final String run = "it-" + UUID.randomUUID();

    @BeforeEach
    void seed() {
        neo4j.query("""
                CREATE (a:Company {id: $run + '-a', name: $run + ' Bank A', kind: 'BANK'})
                CREATE (b:Company {id: $run + '-b', name: $run + ' Bank B', kind: 'BANK'})
                CREATE (a)-[:LISTED_AS {primary: true}]->(:Ticker {id: randomUUID(), symbol: 'ITGA', exchange: $run, currency: 'USD'})
                CREATE (b)-[:LISTED_AS {primary: true}]->(:Ticker {id: randomUUID(), symbol: 'ITGB', exchange: $run, currency: 'USD'})
                CREATE (recent:Story {id: $run + '-recent', title: 'A and B merge', articleCount: 3})
                CREATE (old:Story {id: $run + '-old', title: 'Old A news', articleCount: 1})
                CREATE (recent)-[:AFFECTS {firstSeen: datetime() - duration({days: 2}), impactScore: 0.9, sentiment: 0.4, car1: 0.031}]->(a)
                CREATE (recent)-[:AFFECTS {firstSeen: datetime() - duration({days: 2}), impactScore: 0.7, sentiment: 0.2}]->(b)
                CREATE (old)-[:AFFECTS {firstSeen: datetime() - duration({days: 90}), impactScore: 0.5, sentiment: -0.3}]->(a)
                """).bind(run).to("run").run();
    }

    @AfterEach
    void cleanUp() {
        neo4j.query("MATCH (n) WHERE n.id STARTS WITH $run OR n.exchange = $run DETACH DELETE n").bind(run).to("run").run();
    }

    @Test
    void recentStoriesAndCoAffectedCompanies() {
        GraphDto g = companyService.graph("ITGA", 30, 50);

        assertThat(g.nodes()).extracting(GraphDto.Node::id)
                .containsExactlyInAnyOrder(run + "-recent", run + "-a", run + "-b");     // old story outside 30 days
        assertThat(g.nodes()).filteredOn(n -> n.id().equals(run + "-b")).singleElement()
                .satisfies(n -> {
                    assertThat(n.type()).isEqualTo("company");
                    assertThat(n.symbol()).isEqualTo("ITGB");
                });
        assertThat(g.edges()).hasSize(2);
        assertThat(g.edges()).filteredOn(e -> e.target().equals(run + "-a")).singleElement()
                .satisfies(e -> {
                    assertThat(e.source()).isEqualTo(run + "-recent");
                    assertThat(e.impactScore()).isEqualTo(0.9);
                    assertThat(e.car1()).isEqualTo(0.031);
                });
    }

    @Test
    void longerWindowIncludesOlderStories() {
        GraphDto g = companyService.graph("ITGA", 120, 50);
        assertThat(g.nodes()).extracting(GraphDto.Node::id).contains(run + "-old");
        assertThat(g.edges()).hasSize(3);
    }

    @Test
    void unknownSymbolIsRejectedAndQuietCompanyGivesEmptyGraph() {
        assertThatThrownBy(() -> companyService.graph("NOSUCHTICKER", 30, 50)).isInstanceOf(BusinessException.class);
        neo4j.query("MATCH (:Story {id: $id})-[r:AFFECTS]->() DELETE r").bind(run + "-recent").to("id").run();
        GraphDto g = companyService.graph("ITGA", 30, 50);
        assertThat(g.nodes()).isEmpty();
        assertThat(g.edges()).isEmpty();
    }
}
