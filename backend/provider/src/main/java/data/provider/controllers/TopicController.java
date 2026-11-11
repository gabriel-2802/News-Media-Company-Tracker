package data.provider.controllers;

import data.provider.dto.responses.PageDto;
import data.provider.dto.responses.TopicDto;
import data.provider.services.TopicService;
import data.provider.util.Constants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping(Constants.TOPICS_BASE_PATH)
@Tag(name = "Topics", description = "Querying topics derived from tagged articles.")
public class TopicController {

    private final TopicService topicService;

    @GetMapping
    @Operation(summary = "List all topics", description = "Returns every topic that has been assigned to at least one article, along with its article count.")
    @ApiResponse(responseCode = "200", description = "Topics retrieved successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PageDto.class)))
    public ResponseEntity<PageDto<TopicDto>> getAllTopics(
            @Parameter(description = "Zero-based page index.", example = "0")
            @RequestParam(defaultValue = Constants.DEFAULT_PAGE) final int page,
            @Parameter(description = "Number of topics per page.", example = "20")
            @RequestParam(defaultValue = Constants.DEFAULT_COUNT) final int count,
            @Parameter(description = "Sort field ('count' or 'name')", example = "count")
            @RequestParam(defaultValue = "count") final String sortBy) {
        return ResponseEntity.ok(topicService.getAllTopics(page, count, sortBy));
    }

    @GetMapping(Constants.TOPICS_EXISTS_PATH)
    @Operation(summary = "Check topic existence", description = "Returns whether a topic with the given name already exists.")
    @ApiResponse(responseCode = "200", description = "Existence check result",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Boolean.class)))
    public ResponseEntity<Boolean> exists(
            @Parameter(description = "Name of the topic to check.", example = "regulation_legal_enforcement", required = true)
            @RequestParam final String name) {
        return ResponseEntity.ok(topicService.exists(name));
    }
}
