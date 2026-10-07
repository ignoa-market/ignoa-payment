package io.wisoft.ignoa_payment.toss;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpTossClient implements TossClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";
    private static final String NOT_FOUND_PAYMENT_SESSION = "NOT_FOUND_PAYMENT_SESSION";
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";
    private static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";

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
                        // 5xx·408, 그리고 Toss 형식이 아닌 에러(프록시·WAF 응답 등)는 처리 여부를 알 수 없다.
                        if (status.is5xxServerError() || status.value() == 408) {
                            return new TossConfirmResult.Unknown("HTTP " + status.value());
                        }
                        TossError error = readError(response, status);
                        // 같은 멱등키의 첫 요청이 아직 처리 중이다(Toss 문서: 다시 요청해 응답을 확인). 재조회가 결론 낸다.
                        if (TossError.UNREADABLE_CODE.equals(error.code())
                                || IDEMPOTENT_REQUEST_PROCESSING.equals(error.code())) {
                            return new TossConfirmResult.Unknown("HTTP " + status.value() + " " + error.code());
                        }
                        return switch (error.code()) {
                            case ALREADY_PROCESSED_PAYMENT -> new TossConfirmResult.AlreadyProcessed();
                            case NOT_FOUND_PAYMENT_SESSION -> new TossConfirmResult.SessionExpired();
                            default -> new TossConfirmResult.Rejected(error.code(), error.message());
                        };
                    });
        } catch (RestClientException e) {
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
