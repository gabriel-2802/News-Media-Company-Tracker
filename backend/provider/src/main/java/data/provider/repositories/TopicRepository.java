package data.provider.repositories;

import data.provider.models.Topic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.Optional;

public interface TopicRepository extends Neo4jRepository<Topic, String> {
    Optional<Topic> findByName(String name);

    boolean existsByName(String topic);

    @Query(
            value = """
            MATCH (t:Topic)
            OPTIONAL MATCH (a:Article)-[:HAS_TOPIC]->(t)
            WITH t, count(a) AS articleCount
            RETURN t
            ORDER BY articleCount DESC, t.name ASC
            SKIP $skip LIMIT $limit
            """,
            countQuery = "MATCH (t:Topic) RETURN count(t)"
    )
    Page<Topic> findAllSortedByCount(Pageable pageable);

    @Query(
            value = """
            MATCH (t:Topic)
            RETURN t
            ORDER BY t.name ASC
            SKIP $skip LIMIT $limit
            """,
            countQuery = "MATCH (t:Topic) RETURN count(t)"
    )
    Page<Topic> findAllSortedByName(Pageable pageable);
}
