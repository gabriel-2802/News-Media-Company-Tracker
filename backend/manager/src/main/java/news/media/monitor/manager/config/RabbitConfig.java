package news.media.monitor.manager.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    private static final String PROP_RABBITMQ_URL                    = "${rabbitmq.url}";
    private static final String PROP_ARTICLE_NOTIFICATIONS_QUEUE     = "${rabbitmq.article-notifications-queue}";

    private final String rabbitmqUrl;
    private final String articleNotificationsQueueName;

    public RabbitConfig(@Value(PROP_RABBITMQ_URL) String rabbitmqUrl,
                        @Value(PROP_ARTICLE_NOTIFICATIONS_QUEUE) String articleNotificationsQueueName) {
        this.rabbitmqUrl                   = rabbitmqUrl;
        this.articleNotificationsQueueName = articleNotificationsQueueName;
    }

    @Bean
    public ConnectionFactory connectionFactory() {
        var factory = new CachingConnectionFactory();
        factory.setUri(rabbitmqUrl);
        return factory;
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        var template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
            @Value("${rabbitmq.listeners-auto-startup:true}") boolean autoStartup) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setAutoStartup(autoStartup);
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        // A message that keeps failing is logged and dropped instead of being redelivered forever.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public Queue articleNotificationsQueue() {
        return new Queue(articleNotificationsQueueName, true);
    }

    /** event.upserted from the provider, for watchlist alerts (declared idempotently; setup.sh creates the same). */
    @Bean
    public Queue eventAlertsQueue(@Value("${rabbitmq.event-alerts-queue}") String name) {
        return new Queue(name, true);
    }

    @Bean
    public TopicExchange newsExchange(@Value("${rabbitmq.news-exchange}") String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean
    public Binding eventAlertsBinding(Queue eventAlertsQueue, TopicExchange newsExchange,
                                      @Value("${rabbitmq.event-upserted-routing-key}") String routingKey) {
        return BindingBuilder.bind(eventAlertsQueue).to(newsExchange).with(routingKey);
    }
}
