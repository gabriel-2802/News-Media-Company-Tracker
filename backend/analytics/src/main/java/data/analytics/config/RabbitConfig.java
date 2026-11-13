package data.analytics.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/** Same broker settings as the other services: one RABBITMQ_URL. */
@Configuration
public class RabbitConfig {

    @Bean
    public ConnectionFactory connectionFactory(@Value("${rabbitmq.url}") final String url) {
        final CachingConnectionFactory factory = new CachingConnectionFactory(URI.create(url));
        return factory;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(final ConnectionFactory connectionFactory) {
        final SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new JacksonJsonMessageConverter());
        factory.setPrefetchCount(20);
        // A message that keeps failing is logged and dropped, not redelivered forever;
        // GET /api/events on the provider can rebuild anything missed.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
