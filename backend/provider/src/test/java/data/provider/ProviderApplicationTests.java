package data.provider;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
// Never publish to the shared broker: event.upserted from test fixtures would reach analytics/manager.
@MockitoBean(types = RabbitTemplate.class)
class ProviderApplicationTests {

	@Test
	void contextLoads() {
	}
}
