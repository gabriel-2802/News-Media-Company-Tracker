package data.analytics.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class WebConfig {

    /** ETag on chart responses, so an unchanged chart costs a 304 instead of the full body. */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> chartEtags() {
        final FilterRegistrationBean<ShallowEtagHeaderFilter> bean = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        bean.addUrlPatterns("/api/charts/*");
        return bean;
    }
}
