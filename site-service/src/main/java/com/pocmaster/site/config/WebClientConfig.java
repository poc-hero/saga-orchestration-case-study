package com.pocmaster.site.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean("uaaClient")
    WebClient uaaClient(
            WebClient.Builder builder,
            @Value("${site.services.uaa.url:http://localhost:9091}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
