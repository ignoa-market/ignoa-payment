package io.wisoft.ignoa_payment.toss;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(TossProperties.class)
public class TossClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // api(25초)·CloudFront(30초)보다 먼저 끊고 UNKNOWN으로 넘긴다. 결론은 재조회가 낸다.
    private static final Duration CONFIRM_READ_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration LOOKUP_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public TossClient tossClient(RestClient.Builder builder, TossProperties properties) {
        if (!StringUtils.hasText(properties.secretKey())) {
            throw new IllegalStateException("Toss 시크릿 키가 설정되지 않았습니다.");
        }
        return new HttpTossClient(
                build(builder.clone(), properties, CONFIRM_READ_TIMEOUT),
                build(builder.clone(), properties, LOOKUP_READ_TIMEOUT)
        );
    }

    static String basicAuthorization(String secretKey) {
        String credentials = secretKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static RestClient build(RestClient.Builder builder, TossProperties properties, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuthorization(properties.secretKey()))
                .build();
    }
}
