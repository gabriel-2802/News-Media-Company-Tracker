package data.provider.services;

import data.provider.dto.requests.EnrichmentRequest;
import data.provider.dto.responses.CompanyDto;
import data.provider.dto.responses.GraphDto;
import data.provider.exceptions.BusinessException;
import data.provider.models.CompanyKind;
import data.provider.repositories.ArticleRepository;
import data.provider.repositories.CompanyRepository;
import data.provider.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import data.provider.config.Neo4jWrites;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final ArticleRepository articleRepository;
    private final EventService eventService;
    private final Neo4jWrites writes;
    private final Neo4jClient neo4j;

    public List<CompanyDto> getCompanies(final CompanyKind kind) {
        return companyRepository.findAll().stream()
                .filter(c -> kind == null || c.getKind() == kind)
                .sorted(Comparator.comparing(c -> c.getName().toLowerCase()))
                .map(CompanyDto::new)
                .toList();
    }

    /**
     * Graph around the company listed under `symbol`: its stories first seen
     * in the last `days` days (at most `limit`, highest impact first) and
     * every company those stories affect.
     */
    public GraphDto graph(final String symbol, final int days, final int limit) {
        final List<Map<String, Object>> rows = new ArrayList<>(neo4j.query("""
                MATCH (c:Company)-[:LISTED_AS]->(:Ticker {symbol: $symbol})
                WITH c LIMIT 1
                MATCH (s:Story)-[r:AFFECTS]->(c)
                WHERE r.firstSeen >= datetime() - duration({days: $days})
                WITH c, s, r ORDER BY r.impactScore DESC LIMIT $limit
                MATCH (s)-[e:AFFECTS]->(other:Company)
                OPTIONAL MATCH (other)-[:LISTED_AS {primary: true}]->(t:Ticker)
                RETURN c.id AS centre, s.id AS storyId, s.title AS storyTitle, s.articleCount AS storyArticles,
                       toString(r.firstSeen) AS firstSeen, other.id AS companyId, other.name AS companyName,
                       other.kind AS kind, t.symbol AS symbol, e.impactScore AS impact, e.sentiment AS sentiment, e.car1 AS car1
                """).bindAll(Map.of("symbol", symbol, "days", days, "limit", limit)).fetch().all());
        if (rows.isEmpty()) {
            final boolean exists = neo4j.query("MATCH (:Company)-[:LISTED_AS]->(t:Ticker {symbol: $symbol}) RETURN count(t) > 0")
                    .bind(symbol).to("symbol").fetchAs(Boolean.class).one().orElse(false);
            if (!exists) {
                throw new BusinessException(Constants.TICKER_DOES_NOT_EXIST_ERROR, symbol);
            }
            return new GraphDto(List.of(), List.of());
        }
        final Map<String, GraphDto.Node> nodes = new LinkedHashMap<>();
        final List<GraphDto.Edge> edges = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            final String storyId = (String) r.get("storyId");
            nodes.putIfAbsent(storyId, new GraphDto.Node(storyId, "story", (String) r.get("storyTitle"), null, null,
                    (String) r.get("firstSeen"), r.get("storyArticles") == null ? null : ((Number) r.get("storyArticles")).intValue()));
            final String companyId = (String) r.get("companyId");
            nodes.putIfAbsent(companyId, new GraphDto.Node(companyId, "company", (String) r.get("companyName"),
                    (String) r.get("kind"), (String) r.get("symbol"), null, null));
            edges.add(new GraphDto.Edge(storyId, companyId, (Double) r.get("impact"), (Double) r.get("sentiment"), (Double) r.get("car1")));
        }
        return new GraphDto(List.copyOf(nodes.values()), edges);
    }

    /**
     * Stores the enricher's output for one article: sentiment on the article,
     * and its MENTIONS edges replaced by the given set. Then refreshes the
     * events of the article's story.
     */
    public int setEnrichment(final EnrichmentRequest request) {
        return writes.write("setEnrichment", () -> {
            if (!articleRepository.existsByUrl(request.url())) {
                throw new BusinessException(Constants.ARTICLE_DOES_NOT_EXIST_ERROR, request.url());
            }
            final List<String> ids = request.mentions().stream().map(EnrichmentRequest.Mention::companyId).distinct().toList();
            final long known = neo4j.query("MATCH (c:Company) WHERE c.id IN $ids RETURN count(c)")
                    .bind(ids).to("ids").fetchAs(Long.class).one().orElse(0L);
            if (known != ids.size()) {
                throw new BusinessException(Constants.COMPANY_DOES_NOT_EXIST_ERROR, ids.toString());
            }

            eventService.lockStoryOfArticle(request.url());   // lock order: story first, then companies
            final List<Map<String, Object>> mentions = request.mentions().stream()
                    .sorted(Comparator.comparing(EnrichmentRequest.Mention::companyId))   // sorted company locks
                    .map(m -> {
                final Map<String, Object> row = new HashMap<>();
                row.put("companyId", m.companyId());
                row.put("count", m.count());
                row.put("salience", m.salience());
                row.put("inTitle", m.inTitle());
                row.put("sentiment", m.sentiment());     // may be null
                return row;
            }).toList();

            neo4j.query("""
                    MATCH (a:Article {url: $url})
                    SET a.sentimentLabel = $label, a.sentimentScore = $score
                    WITH a
                    OPTIONAL MATCH (a)-[old:MENTIONS]->(:Company)
                    DELETE old
                    """)
                    .bindAll(Map.of("url", request.url(), "label", request.sentimentLabel(), "score", request.sentimentScore()))
                    .run();
            if (!mentions.isEmpty()) {
                neo4j.query("""
                        MATCH (a:Article {url: $url})
                        UNWIND $mentions AS m
                        MATCH (c:Company {id: m.companyId})
                        MERGE (a)-[r:MENTIONS]->(c)
                        SET r.count = m.count, r.salience = m.salience, r.inTitle = m.inTitle, r.sentiment = m.sentiment
                        """)
                        .bindAll(Map.of("url", request.url(), "mentions", mentions))
                        .run();
            }
            eventService.refreshForArticle(request.url());
            log.info(Constants.ARTICLE_ENRICHED_LOG, request.url(), mentions.size(), request.sentimentLabel());
            return mentions.size();
        });
    }
}
