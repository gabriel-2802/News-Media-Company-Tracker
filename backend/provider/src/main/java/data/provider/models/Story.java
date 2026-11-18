package data.provider.models;

import data.provider.util.UuidStringIdGenerator;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Node("Story")
@Data
@Builder
public class Story {

    @Id
    @GeneratedValue(UuidStringIdGenerator.class)
    private String id;

    /**
     * A story groups the articles about one piece of news; events, chart
     * markers and alerts are keyed on it. Since clustering was removed every
     * article gets its own story when it is saved (StoryRepository
     * .createForArticle); older stories can hold several articles.
     *
     * Title of the story is the title of its (first) article.
     */
    private String title;

    /** When this Story was created (when its article was saved). */
    private Instant createdAt;

    /** Last change to the story's membership; orders the story list. */
    private Instant lastUpdated;

    /** Denormalized article count (1 for stories created since clustering was removed). */
    private int articleCount = 0;

    /** Distinct source count; feeds the "breadth" term of event impact. */
    private int sourceCount = 0;

    /** Ranking signal reserved for a feed; not computed (0). */
    private double trendingScore = 0.0;

    /**
     * Articles belonging to this Story. Lazy by default in SDN — don't
     * fetch this for feed list views (§10 /feed), only for the
     * /feed/story/{id} detail view where you actually need every article.
     */
    @Builder.Default
    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.INCOMING)
    private List<Article> articles = new ArrayList<>();
}