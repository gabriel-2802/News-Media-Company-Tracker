package data.provider.controllers;

import data.provider.dto.responses.ErrorResponse;
import data.provider.dto.responses.PageDto;
import data.provider.dto.responses.StoryDto;
import data.provider.services.StoryService;
import data.provider.util.Constants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping(Constants.STORIES_BASE_PATH)
@Tag(name = "Stories", description = "Stories: one per article (created when the article is saved). Events, chart markers and alerts are keyed on them.")
public class StoryController {

    private final StoryService storyService;

    @GetMapping
    @Operation(summary = "List all stories", description = "Returns a paginated list of all stories, ordered by most recently updated.")
    @ApiResponse(responseCode = "200", description = "Stories retrieved successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PageDto.class)))
    public ResponseEntity<PageDto<StoryDto>> getStories(
            @Parameter(description = "Zero-based page index.", example = "0")
            @RequestParam(defaultValue = Constants.DEFAULT_PAGE) final int page,
            @Parameter(description = "Number of stories per page.", example = "20")
            @RequestParam(defaultValue = Constants.DEFAULT_COUNT) final int count) {
        return ResponseEntity.ok(storyService.getStories(page, count));
    }

    @GetMapping(Constants.STORIES_BY_ARTICLE_PATH)
    @Operation(summary = "Get the story an article belongs to", description = "Returns the story of the article at the given URL.")
    @ApiResponse(responseCode = "200", description = "Story retrieved successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StoryDto.class)))
    @ApiResponse(responseCode = "400", description = "Article does not exist, or is not attached to any story",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<StoryDto> getStoryForArticle(
            @Parameter(description = "Canonical URL of the article.", example = "https://example.com/news/senate-passes-budget", required = true)
            @RequestParam final String url) {
        return ResponseEntity.ok(storyService.getStoryForArticle(url));
    }

    @GetMapping(Constants.STORIES_EXISTS_PATH)
    @Operation(summary = "Check story existence", description = "Returns whether a story with the given ID exists.")
    @ApiResponse(responseCode = "200", description = "Existence check result",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Boolean.class)))
    public ResponseEntity<Boolean> existsById(
            @Parameter(description = "ID of the story to check.", required = true)
            @RequestParam final String storyId) {
        return ResponseEntity.ok(storyService.existsById(storyId));
    }

    @GetMapping("/{storyId}")
    @Operation(summary = "Get a story by ID", description = "Returns a single story and its top articles by ID.")
    @ApiResponse(responseCode = "200", description = "Story retrieved successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StoryDto.class)))
    @ApiResponse(responseCode = "400", description = "Story does not exist",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<StoryDto> getStory(
            @Parameter(description = "ID of the story.", required = true)
            @PathVariable final String storyId) {
        return ResponseEntity.ok(storyService.getStoryById(storyId));
    }

    @GetMapping(Constants.STORIES_SEARCH_PATH)
    @Operation(summary = "Search stories by title", description = "Full-text search over story titles, ordered by relevance. Matching is "
            + "prefix-based per term (e.g. \"sen bud\" matches titles containing words starting with sen and bud).")
    @ApiResponse(responseCode = "200", description = "Matching stories retrieved successfully",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PageDto.class)))
    public ResponseEntity<PageDto<StoryDto>> searchStories(
            @Parameter(description = "Free-text search query.", example = "senate budget", required = true)
            @RequestParam @NotBlank final String q,
            @Parameter(description = "Zero-based page index.", example = "0")
            @RequestParam(defaultValue = Constants.DEFAULT_PAGE) final int page,
            @Parameter(description = "Number of stories per page.", example = "20")
            @RequestParam(defaultValue = Constants.DEFAULT_COUNT) final int count) {
        return ResponseEntity.ok(storyService.searchStories(q, page, count));
    }
}