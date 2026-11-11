package data.provider.models;

import data.provider.util.UuidStringIdGenerator;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.*;

@Node("NewsSource")
@Data
@Builder
public class NewsSource {
    @Id
    @GeneratedValue(UuidStringIdGenerator.class)
    private String id;

    private String name;
    private String baseUrl;
    private String rssUrl;

    /**
     * Classification of the publisher, used for event filters.
     */
    private SourceCategory category;
    private Region region;
    @Builder.Default
    private Boolean paywalled = false;
    @Builder.Default
    private String language = "en";

    /**
     * Data for disabling a source which cannot be fetched from
     */
    @Builder.Default
    private Integer failureCount = 0;
    @Builder.Default
    private Boolean isDisabled = false;

    private String notes;
}
