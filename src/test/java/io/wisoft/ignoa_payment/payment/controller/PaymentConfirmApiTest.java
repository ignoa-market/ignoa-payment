package io.wisoft.ignoa_payment.payment.controller;

import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.service.PaymentReader;
import io.wisoft.ignoa_payment.payment.service.PaymentService;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.toss.TossConfirmResult;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import io.wisoft.ignoa_payment.toss.TossPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentConfirmApiTest extends IntegrationTestSupport {

    private static final long TRADE_ID = 10L;
    private static final long AMOUNT = 50_000L;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReader paymentReader;

    @Autowired
    private PaymentCallbackRepository callbackRepository;

    private Payment prepare() {
        return paymentService.prepare(TRADE_ID, AMOUNT, "상품");
    }

    private static TossPayment tossPayment(String orderId, String status) {
        return new TossPayment("pk", orderId, status, AMOUNT, OffsetDateTime.parse("2026-09-25T14:03:11+09:00"));
    }

    private ResultActions confirm(long tradeId, String orderId, String paymentKey, long amount) throws Exception {
        return mockMvc.perform(post("/internal/payments/confirm")
                .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "trade_id", tradeId, "order_id", orderId, "payment_key", paymentKey, "amount", amount))));
    }

    @Test
    void 승인되면_DONE을_반환하고_콜백을_적재한다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.Responded(tossPayment(payment.getOrderId(), "DONE")));

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.trade_id").value(TRADE_ID))
                .andExpect(jsonPath("$.data.approved_at").value("2026-09-25T14:03:11"))
                .andExpect(jsonPath("$.message").value("결제 승인 요청을 처리했습니다."));

        Payment found = paymentReader.getById(payment.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(found.getPaidTradeId()).isEqualTo(TRADE_ID);
        assertThat(callbackRepository.count()).isEqualTo(1);
    }

    @Test
    void 다른_trade의_order_id면_409이고_Toss를_호출하지_않는다() throws Exception {
        Payment payment = prepare();

        confirm(999L, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_TRADE_MISMATCH"));

        verify(tossClient, never()).confirm(anyString(), anyString(), anyLong());
        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.READY);
    }

    @Test
    void 금액이_다르면_FAILED이고_Toss를_호출하지_않는다() throws Exception {
        Payment payment = prepare();

        confirm(TRADE_ID, payment.getOrderId(), "pk", 100L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure_code").value("AMOUNT_MISMATCH"));

        verify(tossClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 같은_trade에_이미_성공_결제가_있으면_ALREADY_PAID이고_Toss를_호출하지_않는다() throws Exception {
        Payment paid = prepare();
        paymentService.markDone(paid.getId(), "pk-old", LocalDateTime.of(2026, 9, 25, 12, 0), "DONE",
                LocalDateTime.of(2026, 9, 25, 12, 0));
        Payment second = prepare();

        confirm(TRADE_ID, second.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure_code").value("ALREADY_PAID"));

        verify(tossClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void Toss가_거절하면_TOSS_REJECTED와_메시지를_반환한다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.Rejected("REJECT_CARD_PAYMENT", "한도초과"));

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure_code").value("TOSS_REJECTED"))
                .andExpect(jsonPath("$.data.failure_message").value("REJECT_CARD_PAYMENT: 한도초과"));
    }

    @Test
    void 결제_세션이_만료되면_EXPIRED다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.SessionExpired());

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.failure_code").value("EXPIRED"));
    }

    @Test
    void 결과를_모르면_UNKNOWN이고_CONFIRMING으로_남으며_콜백은_없다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.Unknown("SocketTimeoutException"));

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));

        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.CONFIRMING);
        assertThat(callbackRepository.count()).isZero();
    }

    @Test
    void 이미_처리된_결제면_조회해서_실제_상태를_반영한다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.AlreadyProcessed());
        given(tossClient.getByOrderId(payment.getOrderId()))
                .willReturn(new TossLookupResult.Found(tossPayment(payment.getOrderId(), "DONE")));

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.status").value("DONE"));
    }

    @Test
    void 이미_처리된_결제인데_조회도_실패하면_UNKNOWN이다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.AlreadyProcessed());
        given(tossClient.getByOrderId(payment.getOrderId()))
                .willReturn(new TossLookupResult.Failed("HTTP 503"));

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));
    }

    @Test
    void DONE인_결제를_다시_승인하면_같은_결과를_주고_Toss는_한_번만_호출한다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.Responded(tossPayment(payment.getOrderId(), "DONE")));

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT).andExpect(jsonPath("$.data.status").value("DONE"));
        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT).andExpect(jsonPath("$.data.status").value("DONE"));

        verify(tossClient, times(1)).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void FAILED인_결제를_다시_승인하면_저장된_FAILED를_주고_Toss를_호출하지_않는다() throws Exception {
        Payment payment = prepare();
        confirm(TRADE_ID, payment.getOrderId(), "pk", 1L); // 금액 불일치로 FAILED

        confirm(TRADE_ID, payment.getOrderId(), "pk-other", AMOUNT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failure_code").value("AMOUNT_MISMATCH"));

        verify(tossClient, never()).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 승인_중인_결제에_다른_payment_key가_오면_409다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.Unknown("timeout"));
        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT);

        confirm(TRADE_ID, payment.getOrderId(), "pk-other", AMOUNT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_KEY_MISMATCH"));
    }

    @Test
    void 승인_중인_결제를_같은_키로_다시_요청하면_UNKNOWN이고_Toss를_다시_부르지_않는다() throws Exception {
        Payment payment = prepare();
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willReturn(new TossConfirmResult.Unknown("timeout"));
        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT);

        confirm(TRADE_ID, payment.getOrderId(), "pk", AMOUNT)
                .andExpect(jsonPath("$.data.status").value("UNKNOWN"));

        verify(tossClient, times(1)).confirm(anyString(), anyString(), anyLong());
    }

    @Test
    void 없는_order_id면_404다() throws Exception {
        confirm(TRADE_ID, "IGN-none", "pk", AMOUNT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void 필수값이_없으면_400이다() throws Exception {
        mockMvc.perform(post("/internal/payments/confirm")
                        .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":\"IGN-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }
}
