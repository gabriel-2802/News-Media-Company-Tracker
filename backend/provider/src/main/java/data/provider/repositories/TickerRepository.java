package data.provider.repositories;

import data.provider.models.Ticker;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;
import java.util.Optional;

public interface TickerRepository extends Neo4jRepository<Ticker, String> {

    Optional<Ticker> findBySymbolAndExchange(String symbol, String exchange);

    List<Ticker> findBySymbol(String symbol);
}
