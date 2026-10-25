package com.matrix.payments.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI paymentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Payment Gateway Service API")
                .version("1.0.0")
                .description("Creates payments, calls a mock gateway, and processes signed gateway callbacks. "
                        + "Use the optional 'scenario' field to tell the mock bank how to behave (test hook).")
                .contact(new Contact().name("Payment Service Team")));
    }
}
