package data.provider.repositories;

import data.provider.models.Company;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.Optional;

/**
 * Companies and the Cypher-managed MENTIONS / AFFECTS relationships
 * (deliberately not mapped on Article/Story; see Company javadoc).
 */
public interface CompanyRepository extends Neo4jRepository<Company, String> {

    Optional<Company> findByName(String name);

    Optional<Company> findByLei(String lei);

    @Query("""
            MATCH (c:Company)-[:LISTED_AS]->(:Ticker {symbol: $symbol})
            WITH DISTINCT c LIMIT 1
            RETURN c, [(c)-[l:LISTED_AS]->(tk:Ticker) | l], [(c)-[:LISTED_AS]->(tk:Ticker) | tk]
            """)
    Optional<Company> findByTickerSymbol(String symbol);

    @Query("""
            MATCH (a:Article {url: $articleUrl}), (c:Company {id: $companyId})
            MERGE (a)-[m:MENTIONS]->(c)
            SET m.count = $count, m.salience = $salience, m.inTitle = $inTitle, m.sentiment = $sentiment
            """)
    void upsertMention(String articleUrl, String companyId, int count, double salience,
                       boolean inTitle, Double sentiment);

    @Query("""
            MATCH (s:Story {id: $storyId}), (c:Company {id: $companyId})
            MERGE (s)-[r:AFFECTS]->(c)
              ON CREATE SET r.firstSeen = $firstSeen
            SET r.firstSeen = CASE WHEN $firstSeen < r.firstSeen THEN $firstSeen ELSE r.firstSeen END,
                r.impactScore = $impactScore
            """)
    void upsertAffects(String storyId, String companyId, java.time.ZonedDateTime firstSeen, double impactScore);
}
