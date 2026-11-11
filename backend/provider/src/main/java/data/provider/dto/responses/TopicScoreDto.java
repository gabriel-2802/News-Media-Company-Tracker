package data.provider.dto.responses;

import data.provider.models.TopicAssignment;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A topic assigned to an article, with the classifier's confidence.")
public record TopicScoreDto(
        @Schema(description = "Topic name.", example = "regulation_legal_enforcement")
        String name,

        @Schema(description = "Classifier confidence in [0, 1].", example = "0.87")
        Double score) {

    public TopicScoreDto(TopicAssignment assignment) {
        this(assignment.getTopic().getName(), assignment.getScore());
    }
}
