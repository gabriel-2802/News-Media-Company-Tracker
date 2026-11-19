package data.analytics.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger / OpenAPI metadata, in line with the provider and manager. Unlike the provider, the bearer
 * requirement is not global: chart reads are public, and only the admin endpoints declare it
 * (AdminController), so the lock icons in Swagger UI match what the API enforces.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";
    private static final String BEARER_SCHEME = "bearer";
    private static final String BEARER_FORMAT = "JWT";

    private static final List<Tag> TAGS_IN_ORDER = List.of(
            new Tag().name("Charts").description("Public, read-only series for investor charts: prices, event markers, "
                    + "sentiment, news and topic volume, event impact, sector heatmap, quotes. Cached 60 s with ETags."),
            new Tag().name("Admin").description("Run analytics jobs on demand. Requires a ROLE_ADMIN or ROLE_SYSTEM token "
                    + "issued by the manager."));

    @Bean
    OpenAPI analyticsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Market Analytics API")
                        .description("""
                                Market data, event studies and chart APIs for the banking and fintech news monitor. \
                                Keeps a read model of the provider's Story→Company events (fed by RabbitMQ), daily \
                                prices for US listings, and market-model event studies written back to the provider. \
                                Times are unix seconds, so the arrays go straight into TradingView Lightweight Charts \
                                or ECharts.

                                **Authentication:** the chart endpoints are public. The admin endpoints need a JWT \
                                from the manager (`POST /manager/api/auth/login`); click *Authorize* and paste the \
                                `token` value. Tokens are validated with the manager's public key.""")
                        .version("v1")
                        .contact(new Contact()
                                .name("Gabriel Cărăuleanu")
                                .email("gabrielvalentine738@gmail.com")))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme(BEARER_SCHEME)
                                        .bearerFormat(BEARER_FORMAT)
                                        .description("JWT from the manager's login (ROLE_ADMIN or ROLE_SYSTEM).")))
                .tags(TAGS_IN_ORDER);
    }
}
