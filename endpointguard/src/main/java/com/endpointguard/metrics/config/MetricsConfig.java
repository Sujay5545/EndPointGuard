package com.endpointguard.metrics.config;

import com.endpointguard.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class MetricsConfig {
    @Primary
    @Bean
    public RestTemplate restTemplate(AppProperties properties) {
        return createRestTemplate(properties.getHttp().getSharedTimeoutSeconds());
    }

    @Bean("llmRestTemplate")
    public RestTemplate llmRestTemplate(AppProperties properties) {
        return createRestTemplate(properties.getLlm().getTimeoutSeconds());
    }

    private RestTemplate createRestTemplate(int configuredTimeoutSeconds) {
        int timeoutSeconds = Math.max(1, Math.min(60, configuredTimeoutSeconds));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return new RestTemplate(requestFactory);
    }
}
