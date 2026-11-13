package data.provider.dto.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Node-link graph around a company: its stories and the other companies they affect.")
public record GraphDto(List<Node> nodes, List<Edge> edges) {

    @Schema(description = "type is company or story.")
    public record Node(String id, String type, String label, String kind, String symbol, String firstSeen,
                       Integer articleCount) {}

    @Schema(description = "A story -[AFFECTS]-> company edge.")
    public record Edge(String source, String target, Double impactScore, Double sentiment, Double car1) {}
}
