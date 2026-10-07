package io.wisoft.ignoa_payment.toss;

import ch.qos.logback.classic.Level;
import io.wisoft.ignoa_payment.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpTossClientTest {

    private static final String BASE_URL = "https://api.tosspayments.com";

    private MockRestServiceServer server;
    private HttpTossClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new HttpTossClient(restClient, restClient);
    }

    @Test
    void 인증_헤더는_시크릿키_콜론을_base64로_인코딩한다() {
        assertThat(TossClientConfig.basicAuthorization("test_sk"))
                .isEqualTo("Basic dGVzdF9zazo=");
    }

    @Test
    void 승인_성공_응답을_camelCase로_파싱하고_모르는_필드는_무시한다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "IGN-1"))
                .andExpect(jsonPath("$.paymentKey").value("pk"))
                .andExpect(jsonPath("$.orderId").value("IGN-1"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andRespond(withSuccess("""
                        {"mId":"tvivarepublica","paymentKey":"pk","orderId":"IGN-1","status":"DONE",
                         "totalAmount":50000,"approvedAt":"2026-09-25T14:03:11+09:00",
                         "card":{"company":"현대"},"extraField":123}
                        """, MediaType.APPLICATION_JSON));

        TossConfirmResult result = client.confirm("pk", "IGN-1", 50000L);

        assertThat(result).isInstanceOf(TossConfirmResult.Responded.class);
        TossPayment payment = ((TossConfirmResult.Responded) result).payment();
        assertThat(payment.status()).isEqualTo("DONE");
        assertThat(payment.totalAmount()).isEqualTo(50000L);
        assertThat(payment.approvedAtInSeoul()).isEqualTo(LocalDateTime.of(2026, 9, 25, 14, 3, 11));
    }

    @Test
    void approvedAt이_null이어도_파싱한다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withSuccess("""
                        {"paymentKey":"pk","orderId":"IGN-1","status":"WAITING_FOR_DEPOSIT","totalAmount":1000,"approvedAt":null}
                        """, MediaType.APPLICATION_JSON));

        TossConfirmResult result = client.confirm("pk", "IGN-1", 1000L);

        TossPayment payment = ((TossConfirmResult.Responded) result).payment();
        assertThat(payment.approvedAtInSeoul()).isNull();
    }

    @Test
    void 이미_처리된_결제_에러는_AlreadyProcessed다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다\"}"));

        assertThat(client.confirm("pk", "IGN-1", 1000L)).isInstanceOf(TossConfirmResult.AlreadyProcessed.class);
    }

    @Test
    void 결제_세션_만료_에러는_SessionExpired다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND_PAYMENT_SESSION\",\"message\":\"결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.\"}"));

        assertThat(client.confirm("pk", "IGN-1", 1000L)).isInstanceOf(TossConfirmResult.SessionExpired.class);
    }

    @Test
    void 그_외_4xx는_Rejected이고_코드와_메시지를_담는다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족으로 결제에 실패했습니다.\"}"));

        TossConfirmResult result = client.confirm("pk", "IGN-1", 1000L);

        assertThat(result).isEqualTo(new TossConfirmResult.Rejected(
                "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));
    }

    @Test
    void 에러_본문을_읽을_수_없는_4xx는_Toss_응답이_아닐_수_있어_Unknown이다() {
        // 중간 프록시·WAF의 HTML 응답이면 Toss가 실제로 처리했는지 알 수 없다. 재조회가 결론 내게 한다.
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("<html>bad</html>"));

        TossConfirmResult result = client.confirm("pk", "IGN-1", 1000L);

        assertThat(result).isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 멱등키_요청_처리중_409는_진행_중이라_Unknown이다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"처리 중\"}"));

        assertThat(client.confirm("pk", "IGN-1", 1000L)).isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 요청시간초과_408은_처리_여부를_몰라_Unknown이다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REQUEST_TIMEOUT\",\"message\":\"timeout\"}"));

        assertThat(client.confirm("pk", "IGN-1", 1000L)).isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 서버_오류_5xx는_Unknown이다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"내부 시스템 처리 작업이 실패했습니다.\"}"));

        assertThat(client.confirm("pk", "IGN-1", 1000L)).isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 타임아웃은_Unknown이다() {
        ResponseCreator timeout = request -> {
            throw new SocketTimeoutException("external-secret-message");
        };
        server.expect(requestTo(BASE_URL + "/v1/payments/confirm")).andRespond(timeout);

        try (LogCapture logs = LogCapture.at(HttpTossClient.class, Level.DEBUG)) {
            assertThat(client.confirm("pk", "IGN-1", 1000L)).isInstanceOf(TossConfirmResult.Unknown.class);
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("external-secret-message"));
        }
    }

    @Test
    void 조회_타임아웃에서도_외부_예외_메시지를_로그에_남기지_않는다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/IGN-1"))
                .andRespond(request -> { throw new SocketTimeoutException("external-secret-message"); });

        try (LogCapture logs = LogCapture.at(HttpTossClient.class, Level.DEBUG)) {
            assertThat(client.getByOrderId("IGN-1")).isInstanceOf(TossLookupResult.Failed.class);
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("external-secret-message"));
        }
    }

    @Test
    void 주문번호로_조회하면_Found다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/IGN-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"paymentKey":"pk","orderId":"IGN-1","status":"DONE","totalAmount":1000}
                        """, MediaType.APPLICATION_JSON));

        TossLookupResult result = client.getByOrderId("IGN-1");

        assertThat(result).isInstanceOf(TossLookupResult.Found.class);
        assertThat(((TossLookupResult.Found) result).payment().status()).isEqualTo("DONE");
    }

    @Test
    void 조회_404는_NotFound다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/IGN-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND_PAYMENT\",\"message\":\"존재하지 않는 결제 정보 입니다.\"}"));

        assertThat(client.getByOrderId("IGN-1")).isInstanceOf(TossLookupResult.NotFound.class);
    }

    @Test
    void 조회_404라도_결제_없음_코드가_아니면_Failed다() {
        // 키·상점 설정 오류를 "결제 없음"으로 오인하면 재조회가 성공한 결제를 만료 처리할 수 있다.
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/IGN-1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND_MERCHANT\",\"message\":\"존재하지 않는 상점 정보 입니다.\"}"));

        assertThat(client.getByOrderId("IGN-1")).isInstanceOf(TossLookupResult.Failed.class);
    }

    @Test
    void 조회_5xx는_Failed다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/orders/IGN-1"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(client.getByOrderId("IGN-1")).isInstanceOf(TossLookupResult.Failed.class);
    }

    @Test
    void 취소는_멱등키와_사유를_보내고_2xx면_true다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "cancel-pk"))
                .andExpect(content().json("{\"cancelReason\":\"중복 결제\"}"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        assertThat(client.cancel("pk", "중복 결제")).isTrue();
    }

    @Test
    void 취소_실패는_false다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(client.cancel("pk", "중복 결제")).isFalse();
    }

    @Test
    void 취소_전송_예외는_결제_키와_예외_원문을_로그에_남기지_않는다() {
        server.expect(requestTo(BASE_URL + "/v1/payments/pk-secret/cancel"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("external-secret-message");
                });

        try (LogCapture logs = LogCapture.at(HttpTossClient.class, Level.DEBUG)) {
            assertThat(client.cancel("pk-secret", "중복 결제")).isFalse();

            assertThat(logs.events()).noneMatch(event ->
                    event.getFormattedMessage().contains("pk-secret")
                            || event.getFormattedMessage().contains("external-secret-message"));
        }
    }
}
