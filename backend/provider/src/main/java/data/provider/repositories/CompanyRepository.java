package data.provider.repositories;

import data.provider.models.Company;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.Optional;

/**
 * Companies and their listings. MENTIONS / AFFECTS are written by
 * CompanyService (enrichment) and EventService (events) with Cypher.
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
}
