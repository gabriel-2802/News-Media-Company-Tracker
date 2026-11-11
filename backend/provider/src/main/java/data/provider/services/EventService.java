package data.provider.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Maintains events: (:Story)-[:AFFECTS {firstSeen, impactScore, articleCount, sentiment}]->(:Company).
 *
 * Events are derived data. They are recomputed for a story whenever one of
 * their inputs changes (an article is enriched, joins a story, or gets new
 * topics). Recomputing is idempotent and order-independent, which matters
 * because enrichment and clustering run in parallel.
 *
 * impactScore in [0, 1] =
 *     0.25 * breadth    (story sourceCount / 5, capped)
 *   + 0.15 * coverage   (story articles mentioning the company / 3, capped)
 *   + 0.20 * salience   (strongest mention of the company in the story)
 *   + 0.25 * topic      (max over the story's articles of topicWeight * topic score)
 *   + 0.15 * |sentiment| (mean company sentiment in the story)
 *   + 0.10 if a regulator or central bank published part of the story
 * capped at 1.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventService {

    /** How much a topic matters for an investor-facing event. Unlisted topics weigh 0.2. */
    static final Map<String, Object> TOPIC_WEIGHTS = Map.ofEntries(
            Map.entry("regulation_legal_enforcement", 1.0),
            Map.entry("m_and_a_investments", 1.0),
            Map.entry("earnings_results", 0.8),
            Map.entry("monetary_policy_rates", 0.6),
            Map.entry("leadership_personnel", 0.6),
            Map.entry("debt_capital_markets", 0.6),
            Map.entry("dividends_buybacks", 0.5),
            Map.entry("analyst_ratings", 0.5),
            Map.entry("company_product_news", 0.5),
            Map.entry("markets_stock_moves", 0.4),
            Map.entry("fx_currencies", 0.3),
            Map.entry("macro_economy", 0.3),
            Map.entry("other", 0.2));

    private static final String DROP_STALE = """
            MATCH (:Article {url: $url})-[:BELONGS_TO]->(s:Story)-[old:AFFECTS]->(c:Company)
            WHERE NOT EXISTS { (s)<-[:BELONGS_TO]-(:Article)-[:MENTIONS]->(c) }
            DELETE old
            """;

    private static final String REFRESH = """
            MATCH (:Article {url: $url})-[:BELONGS_TO]->(s:Story)
            MATCH (s)<-[:BELONGS_TO]-(a:Article)-[m:MENTIONS]->(c:Company)
            WITH s, c, count(DISTINCT a) AS mentioning, min(a.publishedAt) AS firstLocal,
                 max(m.salience) AS salience, avg(coalesce(m.sentiment, a.sentimentScore, 0.0)) AS sentiment
            WITH s, c, mentioning, firstLocal, salience, sentiment,
                 reduce(best = 0.0, x IN [(s)<-[:BELONGS_TO]-(:Article)-[ht:HAS_TOPIC]->(t:Topic)
                                          | coalesce($weights[t.name], 0.2) * coalesce(ht.score, 1.0)]
                        | CASE WHEN x > best THEN x ELSE best END) AS topic,
                 EXISTS { (s)<-[:BELONGS_TO]-(:Article)<-[:PUBLISHED]-(src:NewsSource)
                          WHERE src.category IN ['REGULATOR', 'CENTRAL_BANK'] } AS official
            WITH s, c, mentioning, firstLocal, sentiment,
                 0.25 * CASE WHEN coalesce(s.sourceCount, 1) >= 5 THEN 1.0 ELSE coalesce(s.sourceCount, 1) / 5.0 END
               + 0.15 * CASE WHEN mentioning >= 3 THEN 1.0 ELSE mentioning / 3.0 END
               + 0.20 * coalesce(salience, 0.0)
               + 0.25 * topic
               + 0.15 * abs(sentiment)
               + CASE WHEN official THEN 0.10 ELSE 0.0 END AS raw
            ORDER BY c.id   // consistent lock order across transactions
            MERGE (s)-[r:AFFECTS]->(c)
            SET r.firstSeen = CASE WHEN firstLocal IS NULL THEN coalesce(r.firstSeen, datetime())
                                   ELSE datetime({datetime: firstLocal, timezone: 'UTC'}) END,
                r.impactScore = round(CASE WHEN raw > 1.0 THEN 1.0 ELSE raw END, 3),
                r.articleCount = mentioning,
                r.sentiment = round(sentiment, 3)
            RETURN count(r) AS events
            """;

    private final Neo4jClient neo4j;

    /*
     * Lock ordering. Story attach, enrichment and topic writes all end up
     * writing the story's AFFECTS events; without a common order they locked
     * article/story/company nodes in different sequences and deadlocked under
     * load. Every such write therefore takes the story's lock first (writes to
     * one story queue up), and companies/topics are written in sorted order.
     */

    /** Take the write lock of a story (no-op if it does not exist). */
    public void lockStory(final String storyId) {
        neo4j.query("MATCH (s:Story {id: $id}) SET s.lastWriteAt = datetime()").bind(storyId).to("id").run();
    }

    /** Take the write lock of the story this article belongs to (no-op if not clustered). */
    public void lockStoryOfArticle(final String articleUrl) {
        neo4j.query("MATCH (:Article {url: $url})-[:BELONGS_TO]->(s:Story) SET s.lastWriteAt = datetime()")
                .bind(articleUrl).to("url").run();
    }

    /** Recompute all events of the story this article belongs to (no-op if it is not clustered yet). */
    public long refreshForArticle(final String articleUrl) {
        neo4j.query(DROP_STALE).bind(articleUrl).to("url").run();
        final long events = neo4j.query(REFRESH)
                .bindAll(Map.of("url", articleUrl, "weights", TOPIC_WEIGHTS))
                .fetchAs(Long.class).one().orElse(0L);
        log.debug("Refreshed {} event(s) for the story of {}", events, articleUrl);
        return events;
    }
}
