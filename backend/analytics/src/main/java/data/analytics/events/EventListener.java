package data.analytics.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Consumes event.upserted (queue analytics.events) into the local read model. */
@Component
@Slf4j
@RequiredArgsConstructor
public class EventListener {

    private final EventRepository events;

    @RabbitListener(queues = "${analytics.events-queue}")
    public void onEvent(final EventMessage message) {
        final boolean changed = events.apply(message);
        log.debug("event {} {}/{} v{} -> {}", message.deleted() ? "delete" : "upsert",
                message.storyId(), message.companyId(), message.version(), changed ? "applied" : "ignored (stale)");
    }
}
