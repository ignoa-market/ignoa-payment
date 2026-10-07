package io.wisoft.ignoa_payment.webhook;

import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.service.PaymentReader;
import io.wisoft.ignoa_payment.payment.service.PaymentService;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.support.LogCapture;
import io.wisoft.ignoa_payment.global.exception.GlobalExceptionHandler;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import io.wisoft.ignoa_payment.toss.TossPayment;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Level;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TossWebhookApiTest extends IntegrationTestSupport {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReader paymentReader;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    private ResultActions webhook(String body) throws Exception {
        return mockMvc.perform(post("/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String statusChanged(String orderId, String status) {
        return """
                {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-09-25T14:03:11.000000",
                 "data":{"paymentKey":"pk","orderId":"%s","status":"%s","totalAmount":1000}}
                """.formatted(orderId, status);
    }

    private WebhookResult lastResult() {
        return webhookEventRepository.findAll().getLast().getResult();
    }

    @Test
    void 본문이_아니라_Toss_조회_결과로_상태를_반영한다() throws Exception {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk", LocalDateTime.of(2026, 9, 25, 12, 0));
        // 본문은 DONE이라고 하지만 실제 조회 결과는 ABORTED
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.Found(
                new TossPayment("pk", payment.getOrderId(), "ABORTED", 1000L, null)));

        webhook(statusChanged(payment.getOrderId(), "DONE")).andExpect(status().isOk());

        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(lastResult()).isEqualTo(WebhookResult.APPLIED);
    }

    @Test
    void 모르는_order_id는_200_IGNORED이고_Toss를_조회하지_않는다() throws Exception {
        webhook(statusChanged("IGN-unknown", "DONE")).andExpect(status().isOk());

        verify(tossClient, never()).getByOrderId(anyString());
        assertThat(lastResult()).isEqualTo(WebhookResult.IGNORED);
    }

    @Test
    void 결제_상태_이벤트가_아니면_200_IGNORED다() throws Exception {
        webhook("{\"eventType\":\"DEPOSIT_CALLBACK\",\"data\":{\"orderId\":\"IGN-1\"}}")
                .andExpect(status().isOk());

        assertThat(lastResult()).isEqualTo(WebhookResult.IGNORED);
    }

    @Test
    void JSON이_아닌_본문도_200_IGNORED이고_원문을_남긴다() throws Exception {
        webhook("not-a-json").andExpect(status().isOk());

        WebhookEvent event = webhookEventRepository.findAll().getLast();
        assertThat(event.getResult()).isEqualTo(WebhookResult.IGNORED);
        assertThat(event.getRawBody()).isEqualTo("not-a-json");
    }

    @Test
    void 잘못된_웹훅_본문을_로그에_노출하지_않는다() throws Exception {
        try (LogCapture logs = LogCapture.at(WebhookService.class, Level.DEBUG)) {
            webhook("{\"secret-marker\":").andExpect(status().isOk());
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("secret-marker"));
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("reason="));
        }
    }

    @Test
    void orderId가_없으면_200_IGNORED다() throws Exception {
        webhook("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{}}").andExpect(status().isOk());

        assertThat(lastResult()).isEqualTo(WebhookResult.IGNORED);
    }

    @Test
    void Toss_조회가_실패하면_500으로_재전송을_유도한다() throws Exception {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.Failed("HTTP 503"));

        try (LogCapture logs = LogCapture.at(GlobalExceptionHandler.class, Level.DEBUG)) {
            webhook(statusChanged(payment.getOrderId(), "DONE")).andExpect(status().isInternalServerError());
            assertThat(logs.events()).noneMatch(event -> event.getLevel() == Level.ERROR);
        }

        assertThat(lastResult()).isEqualTo(WebhookResult.FAILED);
    }

    @Test
    void 내부_API_키_없이_호출할_수_있다() throws Exception {
        webhook(statusChanged("IGN-unknown", "DONE")).andExpect(status().isOk());
    }
}
