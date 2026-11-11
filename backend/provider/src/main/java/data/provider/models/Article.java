package data.provider.models;

import data.provider.util.UuidStringIdGenerator;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Node("Article")
@Data
@Builder
public class Article {

    @Id
    @GeneratedValue(UuidStringIdGenerator.class)
    private String id;

    private String author;
    private String title;
    private String url;
    private String bodyText;
    private LocalDateTime publishedAt;
    @Builder.Default
    private String language = "en";

    /** FinBERT sentiment of title + lead: "positive" | "negative" | "neutral". Set by the enricher. */
    private String sentimentLabel;
    /** Signed sentiment in [-1, 1] (positive prob - negative prob). */
    private Double sentimentScore;

    @Relationship(type = "PUBLISHED", direction = Relationship.Direction.INCOMING)
    private NewsSource newsSource;

    /** Multi-label topics with classifier scores. */
    @Builder.Default
    @Relationship(type = "HAS_TOPIC", direction = Relationship.Direction.OUTGOING)
    private List<TopicAssignment> topics = new ArrayList<>();

    /** Highest-scoring topic, if any. */
    public Optional<TopicAssignment> primaryTopic() {
        if (topics == null) {
            return Optional.empty();
        }
        return topics.stream()
                .filter(t -> t.getTopic() != null)
                .max(Comparator.comparing(t -> t.getScore() != null ? t.getScore() : 0.0));
    }
}
