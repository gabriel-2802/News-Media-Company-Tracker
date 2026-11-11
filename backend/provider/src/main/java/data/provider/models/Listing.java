package data.provider.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/** (:Company)-[:LISTED_AS {primary}]->(:Ticker). */
@RelationshipProperties
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Listing {

    @RelationshipId
    private String id;

    /** True for the listing used for charts and event studies. */
    @Builder.Default
    private Boolean primary = false;

    @TargetNode
    private Ticker ticker;
}
