package data.provider.models;

import data.provider.util.UuidStringIdGenerator;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

/**
 * A bank, fintech, regulator or central bank that articles can mention.
 *
 * Article-[:MENTIONS]->Company and Story-[:AFFECTS]->Company are written
 * and read with Cypher only (see CompanyRepository), not mapped here or on
 * Article/Story: Spring Data Neo4j's save() deletes relationships that are
 * not loaded on the entity, so mapping them would let an unrelated save
 * (e.g. a story attach) wipe company links.
 */
@Node("Company")
@Data
@Builder
public class Company {
    @Id
    @GeneratedValue(UuidStringIdGenerator.class)
    private String id;

    private String name;
    /** ISO 17442 Legal Entity Identifier, when known. */
    private String lei;
    /** ISO 3166-1 alpha-2 country of incorporation. */
    private String country;
    private CompanyKind kind;

    /** Alternative names used for entity linking ("JPMorgan", "Chase", "JPM"). */
    @Builder.Default
    private List<String> aliases = new ArrayList<>();

    @Builder.Default
    @Relationship(type = "LISTED_AS", direction = Relationship.Direction.OUTGOING)
    private List<Listing> listings = new ArrayList<>();
}
