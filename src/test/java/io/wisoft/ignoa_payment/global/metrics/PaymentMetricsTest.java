package io.wisoft.ignoa_payment.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import io.wisoft.ignoa_payment.callback.scheduler.CallbackDispatchJob;
import io.wisoft.ignoa_payment.callback.service.CallbackOutcome;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.scheduler.ConfirmReconcileJob;
import io.wisoft.ignoa_payment.payment.service.PaymentService;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.toss.TossConfirmResult;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class PaymentMetricsTest extends IntegrationTestSupport {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 12, 0, 0);

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentCallbackRepository callbackRepository;

    @Autowired
    private CallbackDispatchJob callbackDispatchJob;

    @Autowired
    private ConfirmReconcileJob confirmReconcileJob;

    private double count(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 승인_결과를_상태별로_센다() throws Exception {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        given(tossClient.confirm(anyString(), anyString(), anyLong()))
                .willReturn(new TossConfirmResult.Unknown("timeout"));
        double before = count("payment.confirm", "result", "UNKNOWN");

        mockMvc.perform(post("/internal/payments/confirm")
                .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "trade_id", 1, "order_id", payment.getOrderId(), "payment_key", "pk", "amount", 1000))));

        assertThat(count("payment.confirm", "result", "UNKNOWN")).isEqualTo(before + 1);
    }

    @Test
    void 콜백_결과를_센다() {
        callbackRepository.save(PaymentCallback.pending(1L, 1L, "{}", T0));
        given(apiCallbackClient.send(anyLong(), anyString())).willReturn(CallbackOutcome.success());
        double before = count("payment.callback", "result", "SENT");

        callbackDispatchJob.execute(T0);

        assertThat(count("payment.callback", "result", "SENT")).isEqualTo(before + 1);
    }

    @Test
    void 재조회_실패를_센다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk", T0);
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.Failed("HTTP 503"));
        double before = count("payment.reconcile", "result", "LOOKUP_FAILED");

        confirmReconcileJob.execute(T0.plusMinutes(2));

        assertThat(count("payment.reconcile", "result", "LOOKUP_FAILED")).isEqualTo(before + 1);
    }

    @Test
    void 웹훅은_모르는_이벤트_타입을_OTHER로_묶어_센다() throws Exception {
        double before = count("payment.webhook", "event_type", "OTHER", "result", "IGNORED");

        mockMvc.perform(post("/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"SOMETHING_NEW\"}"));

        assertThat(count("payment.webhook", "event_type", "OTHER", "result", "IGNORED")).isEqualTo(before + 1);
    }
}
