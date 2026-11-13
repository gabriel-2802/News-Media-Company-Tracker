package data.provider.services;

import data.provider.dto.messages.EventMessage;
import data.provider.dto.requests.ImpactRequest;
import data.provider.exceptions.BusinessException;
import data.provider.util.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Events feed for consumers rebuilding their copy, and the analytics impact write-back. */
@Service
@RequiredArgsConstructor
public class EventQueryService {

    private final Neo4jClient neo4j;

    public List<EventMessage> eventsSince(final String since, final int page, final int count) {
        final Map<String, Object> params = new HashMap<>();
        params.put("since", since);
        params.put("skip", (long) page * count);
        params.put("limit", (long) count);
        return neo4j.query("""
                MATCH (s:Story)-[r:AFFECTS]->(c:Company)
                WHERE $since IS NULL OR r.updatedAt >= datetime($since)
                OPTIONAL MATCH (c)-[:LISTED_AS {primary: true}]->(t:Ticker)
                RETURN s.id AS storyId, c.id AS companyId, c.name AS companyName,
                       t.symbol AS symbol, t.exchange AS exchange, t.currency AS currency,
                       s.title AS storyTitle, toString(r.firstSeen) AS firstSeen, r.impactScore AS impactScore,
                       r.articleCount AS articleCount, r.sentiment AS sentiment,
                       COLLECT { MATCH (s)<-[:BELONGS_TO]-(:Article)-[:HAS_TOPIC]->(tp:Topic) RETURN DISTINCT tp.name ORDER BY tp.name } AS topics,
                       coalesce(r.version, 0) AS version
                ORDER BY r.updatedAt, s.id, c.id
                SKIP $skip LIMIT $limit
                """).bindAll(params).fetch().all().stream()
                .map(row -> EventMessage.fromRow(row, false))
                .toList();
    }

    public void setImpact(final String storyId, final String companyId, final ImpactRequest request) {
        final Map<String, Object> params = new HashMap<>();
        params.put("storyId", storyId);
        params.put("companyId", companyId);
        params.put("benchmark", request.benchmark());
        params.put("ar", request.abnormalReturn());
        params.put("car1", request.car1());
        params.put("car5", request.car5());
        params.put("beta", request.beta());
        params.put("p", request.pValue());
        params.put("days", request.estimationDays());
        final long updated = neo4j.query("""
                MATCH (:Story {id: $storyId})-[r:AFFECTS]->(:Company {id: $companyId})
                SET r.benchmark = $benchmark, r.abnormalReturn = $ar, r.car1 = $car1, r.car5 = $car5,
                    r.beta = $beta, r.pValue = $p, r.estimationDays = $days, r.impactComputedAt = datetime()
                RETURN count(r)
                """).bindAll(params).fetchAs(Long.class).one().orElse(0L);
        if (updated == 0) {
            throw new BusinessException(Constants.EVENT_DOES_NOT_EXIST_ERROR, storyId, companyId);
        }
    }
}
