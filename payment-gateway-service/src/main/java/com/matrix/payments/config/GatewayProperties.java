package com.matrix.payments.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.gateway")
public record GatewayProperties(
        String baseUrl,
        String callbackUrl,
        String callbackSecret,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration retryBackoff,
        Duration mockHangTime,
        Duration mockCallbackDelay,
        Duration mockLateCallbackDelay) {
}
