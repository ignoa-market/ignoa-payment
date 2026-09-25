package io.wisoft.ignoa_payment.toss;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
public class HttpTossClient implements TossClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";
    private static final String NOT_FOUND_PAYMENT_SESSION = "NOT_FOUND_PAYMENT_SESSION";
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";

    private final RestClient confirmClient; // 승인·취소(읽기 20초)
    private final RestClient lookupClient;  // 조회(읽기 5초)

    public HttpTossClient(RestClient confirmClient, RestClient lookupClient) {
        this.confirmClient = confirmClient;
        this.lookupClient = lookupClient;
    }

    @Override
    public TossConfirmResult confirm(String paymentKey, String orderId, long amount) {
        try {
            return confirmClient.post()
                    .uri("/v1/payments/confirm")
                    .header(IDEMPOTENCY_KEY, orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossConfirmRequest(paymentKey, orderId, amount))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return new TossConfirmResult.Responded(response.bodyTo(TossPayment.class));
                        }
                        if (status.is5xxServerError()) {
                            return new TossConfirmResult.Unknown("HTTP " + status.value());
                        }
                        TossError error = readError(response, status);
                        return switch (error.code()) {
                            case ALREADY_PROCESSED_PAYMENT -> new TossConfirmResult.AlreadyProcessed();
                            case NOT_FOUND_PAYMENT_SESSION -> new TossConfirmResult.SessionExpired();
                            default -> new TossConfirmResult.Rejected(error.code(), error.message());
                        };
                    });
        } catch (RestClientException e) {
            log.warn("Toss 승인 응답 미확인: orderId={}, reason={}", orderId, e.getMessage());
            return new TossConfirmResult.Unknown(e.getClass().getSimpleName());
        }
    }

    @Override
    public TossLookupResult getByOrderId(String orderId) {
        try {
            return lookupClient.get()
                    .uri("/v1/payments/orders/{orderId}", orderId)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return new TossLookupResult.Found(response.bodyTo(TossPayment.class));
                        }
                        // 404라도 NOT_FOUND_MERCHANT 같은 설정 오류는 "결제 없음"이 아니다. 판단을 보류한다.
                        TossError error = readError(response, status);
                        if (status.value() == 404 && NOT_FOUND_PAYMENT.equals(error.code())) {
                            return new TossLookupResult.NotFound();
                        }
                        return new TossLookupResult.Failed("HTTP " + status.value() + " " + error.code());
                    });
        } catch (RestClientException e) {
            log.warn("Toss 결제 조회 실패: orderId={}, reason={}", orderId, e.getMessage());
            return new TossLookupResult.Failed(e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean cancel(String paymentKey, String cancelReason) {
        try {
            return confirmClient.post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .header(IDEMPOTENCY_KEY, "cancel-" + paymentKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossCancelRequest(cancelReason))
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
        } catch (RestClientException e) {
            log.warn("Toss 결제 취소 요청 실패: paymentKey={}, reason={}", paymentKey, e.getMessage());
            return false;
        }
    }

    private static TossError readError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                       HttpStatusCode status) {
        try {
            TossError error = response.bodyTo(TossError.class);
            return (error == null || error.code() == null) ? TossError.unreadable(status.value()) : error;
        } catch (RuntimeException e) {
            return TossError.unreadable(status.value());
        }
    }
}
