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

    // 계약 5: 2xx가 아니면 모두 재시도한다. 401(키 교체 중)·404(배포 전)처럼 4xx도 일시적일 수 있다.
    private static CallbackOutcome classify(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return CallbackOutcome.success();
        }
        return CallbackOutcome.retryable("HTTP " + status.value());
    }
}
