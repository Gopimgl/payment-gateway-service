package com.matrix.payments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /** HTTP client used to talk to the (mock) payment gateway, with explicit timeouts. */
    @Bean
    RestClient gatewayRestClient(RestClient.Builder builder, GatewayProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.connectTimeout().toMillis());
        factory.setReadTimeout((int) props.readTimeout().toMillis());
        return builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
    }
}
