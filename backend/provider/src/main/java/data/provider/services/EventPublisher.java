package data.provider.services;

import data.provider.dto.messages.EventMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Publishes event.upserted messages, after the surrounding transaction
 * commits: a rolled-back or retried write (see Neo4jWrites) must never
 * announce an event that does not exist.
 */
@Component
@Slf4j
public class EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public EventPublisher(final RabbitTemplate rabbitTemplate,
                          @Value("${rabbitmq.news-exchange}") final String exchange,
                          @Value("${rabbitmq.event-upserted-routing-key}") final String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(final List<EventMessage> events) {
        if (events.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(events);
                }
            });
        } else {
            send(events);
        }
    }

    private void send(final List<EventMessage> events) {
        events.forEach(e -> rabbitTemplate.convertAndSend(exchange, routingKey, e));
        log.debug("Published {} event.upserted message(s)", events.size());
    }
}
