package data.provider.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * (:Article)-[:HAS_TOPIC {score}]->(:Topic). An article can carry several
 * topics (multi-label classifier), each with the classifier's confidence.
 */
@RelationshipProperties
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicAssignment {

    @RelationshipId
    private String id;

    /** Classifier confidence in [0, 1]. Manually assigned topics use 1.0. */
    private Double score;

    @TargetNode
    private Topic topic;
}
