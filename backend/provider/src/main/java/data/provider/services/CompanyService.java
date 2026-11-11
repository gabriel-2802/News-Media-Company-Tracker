package data.provider.services;

import data.provider.dto.requests.EnrichmentRequest;
import data.provider.dto.responses.CompanyDto;
import data.provider.exceptions.BusinessException;
import data.provider.models.CompanyKind;
import data.provider.repositories.ArticleRepository;
import data.provider.repositories.CompanyRepository;
import data.provider.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final ArticleRepository articleRepository;
    private final EventService eventService;
    private final Neo4jClient neo4j;

    public List<CompanyDto> getCompanies(final CompanyKind kind) {
        return companyRepository.findAll().stream()
                .filter(c -> kind == null || c.getKind() == kind)
                .sorted(Comparator.comparing(c -> c.getName().toLowerCase()))
                .map(CompanyDto::new)
                .toList();
    }

    /**
     * Stores the enricher's output for one article: sentiment on the article,
     * and its MENTIONS edges replaced by the given set. Then refreshes the
     * events of the article's story.
     */
    @Transactional
    public int setEnrichment(final EnrichmentRequest request) {
        if (!articleRepository.existsByUrl(request.url())) {
            throw new BusinessException(Constants.ARTICLE_DOES_NOT_EXIST_ERROR, request.url());
        }
        final List<String> ids = request.mentions().stream().map(EnrichmentRequest.Mention::companyId).distinct().toList();
        final long known = neo4j.query("MATCH (c:Company) WHERE c.id IN $ids RETURN count(c)")
                .bind(ids).to("ids").fetchAs(Long.class).one().orElse(0L);
        if (known != ids.size()) {
            throw new BusinessException(Constants.COMPANY_DOES_NOT_EXIST_ERROR, ids.toString());
        }

        final List<Map<String, Object>> mentions = request.mentions().stream().map(m -> {
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
    }
}
