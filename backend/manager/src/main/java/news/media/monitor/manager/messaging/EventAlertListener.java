package news.media.monitor.manager.messaging;

import lombok.RequiredArgsConstructor;
import news.media.monitor.manager.dto.messages.EventMessage;
import news.media.monitor.manager.services.TickerAlertService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Consumes event.upserted (queue manager.events) and creates watchlist alerts. */
@Component
@RequiredArgsConstructor
public class EventAlertListener {

    private final TickerAlertService alerts;

    @RabbitListener(queues = "${rabbitmq.event-alerts-queue}")
    public void onEvent(EventMessage message) {
        alerts.handle(message);
    }
}
