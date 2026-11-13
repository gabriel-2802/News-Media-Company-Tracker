package data.provider.controllers;

import data.provider.dto.messages.EventMessage;
import data.provider.dto.requests.ImpactRequest;
import data.provider.dto.responses.ErrorResponse;
import data.provider.services.EventQueryService;
import data.provider.util.Constants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(Constants.EVENTS_BASE_PATH)
@Tag(name = "Events", description = "Story-company events (Story -[:AFFECTS]-> Company).")
public class EventController {

    private final EventQueryService eventQueryService;

    @GetMapping
    @Operation(summary = "List events changed since a time",
            description = "Events ordered by last update, for consumers rebuilding their copy (the analytics service). "
                    + "Live changes are also published as event.upserted on RabbitMQ.")
    @ApiResponse(responseCode = "200", description = "Events retrieved",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = EventMessage.class))))
    public ResponseEntity<List<EventMessage>> getEvents(
            @Parameter(description = "ISO-8601 datetime; omit for all events.", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) final String since,
            @RequestParam(defaultValue = "0") @Min(0) final int page,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) final int count) {
        return ResponseEntity.ok(eventQueryService.eventsSince(since, page, count));
    }

    @PatchMapping(Constants.EVENTS_IMPACT_PATH)
    @Operation(summary = "Store the market reaction to an event",
            description = "Written by the analytics service after an event study. Does not publish event.upserted.")
    @ApiResponse(responseCode = "204", description = "Impact stored")
    @ApiResponse(responseCode = "400", description = "Event does not exist or validation failed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> setImpact(@PathVariable final String storyId, @PathVariable final String companyId,
                                          @Valid @RequestBody final ImpactRequest request) {
        eventQueryService.setImpact(storyId, companyId, request);
        return ResponseEntity.noContent().build();
    }
}
