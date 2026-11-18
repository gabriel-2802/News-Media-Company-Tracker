package data.provider.repositories;

import data.provider.models.Story;
import data.provider.util.Constants;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.time.Instant;
import java.util.Optional;

public interface StoryRepository extends Neo4jRepository<Story, String> {

    @Query(
            value = """
            MATCH (story:Story)
            RETURN story
            ORDER BY story.lastUpdated DESC
            SKIP $skip LIMIT $limit
            """,
            countQuery = "MATCH (story:Story) RETURN count(story)"
    )
    Page<Story> findAllPaginated(Pageable pageable);

    /**
     * Gives an article its own one-article story (no-op if it already has one).
     * Every article is its own story since clustering was removed; the Story
     * node stays as the unit that events, chart markers and alerts key on.
     */
    @Query("""
            MATCH (a:Article {url: $articleUrl})
            WHERE NOT (a)-[:BELONGS_TO]->(:Story)
            CREATE (story:Story {id: randomUUID(), title: a.title, createdAt: $now, lastUpdated: $now,
                                 articleCount: 1, sourceCount: 1, trendingScore: 0.0})
            CREATE (a)-[:BELONGS_TO]->(story)
            RETURN story
            """)
    Optional<Story> createForArticle(String articleUrl, Instant now);

    @Query("""
            MATCH (:Article {url: $articleUrl})-[:BELONGS_TO]->(story:Story)
            RETURN story
            """)
    Optional<Story> findByArticleUrl(String articleUrl);

    @Query(
            value = """
                    CALL db.index.fulltext.queryNodes('""" + Constants.STORY_TITLE_FULLTEXT_IDX + """
                    ', $query) YIELD node AS story, score
                    RETURN story
                    ORDER BY score DESC
                    SKIP $skip LIMIT $limit
                    """,
            countQuery = """
                    CALL db.index.fulltext.queryNodes('""" + Constants.STORY_TITLE_FULLTEXT_IDX + """
                    ', $query) YIELD node
                    RETURN count(node)
                    """
    )
    Page<Story> searchByTitle(String query, Pageable pageable);
}