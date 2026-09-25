package io.wisoft.ignoa_payment.callback.client;

import io.wisoft.ignoa_payment.callback.service.CallbackOutcome;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpApiCallbackClient implements ApiCallbackClient {

    private final RestClient restClient;

    public HttpApiCallbackClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CallbackOutcome send(Long tradeId, String payload) {
        try {
            return restClient.post()
                    .uri("/internal/trades/{tradeId}/payment-result", tradeId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> classify(response.getStatusCode()));
        } catch (RestClientException e) {
            return CallbackOutcome.retryable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // 408·429를 뺀 4xx는 다시 보내도 결과가 같으므로 재시도하지 않는다(계약 5).
    private static CallbackOutcome classify(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return CallbackOutcome.success();
        }
        int value = status.value();
        if (status.is4xxClientError() && value != 408 && value != 429) {
            return CallbackOutcome.permanent("HTTP " + value);
        }
        return CallbackOutcome.retryable("HTTP " + value);
    }
}
