package io.wisoft.ignoa_payment.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "toss")
public record TossProperties(
        String baseUrl,
        String secretKey
) {
}
