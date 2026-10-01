package io.wisoft.ignoa_payment.callback.client;

import io.wisoft.ignoa_payment.global.security.InternalApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class CallbackClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public ApiCallbackClient apiCallbackClient(RestClient.Builder builder,
                                               @Value("${ignoa.api-base-url}") String apiBaseUrl,
                                               @Value("${ignoa.internal-api-key}") String internalApiKey) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient restClient = builder.clone()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(InternalApiKeyFilter.HEADER, internalApiKey)
                .build();
        return new HttpApiCallbackClient(restClient);
    }
}
