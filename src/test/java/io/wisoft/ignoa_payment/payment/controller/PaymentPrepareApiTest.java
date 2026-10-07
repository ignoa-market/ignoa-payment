package io.wisoft.ignoa_payment.payment.controller;

import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.support.LogCapture;
import io.wisoft.ignoa_payment.global.exception.GlobalExceptionHandler;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentPrepareApiTest extends IntegrationTestSupport {

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void 결제를_준비하면_READY와_새_order_id를_발급한다() throws Exception {
        mockMvc.perform(post("/internal/payments")
                        .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "trade_id", 123, "amount", 50000, "order_name", "아이폰 15 Pro"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.order_id").value(org.hamcrest.Matchers.matchesPattern("IGN-[0-9a-f]{32}")))
                .andExpect(jsonPath("$.data.trade_id").value(123))
                .andExpect(jsonPath("$.data.amount").value(50000))
                .andExpect(jsonPath("$.message").value("결제를 준비했습니다."));

        assertThat(paymentRepository.findAll())
                .singleElement()
                .satisfies(payment -> {
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
                    assertThat(payment.getOrderName()).isEqualTo("아이폰 15 Pro");
                });
    }

    @Test
    void 같은_trade로_두_번_준비하면_order_id가_서로_다르다() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "trade_id", 123, "amount", 50000, "order_name", "상품"));
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/internal/payments")
                            .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        assertThat(paymentRepository.findAll())
                .extracting(Payment::getOrderId)
                .doesNotHaveDuplicates()
                .hasSize(2);
    }

    @Test
    void 금액이_0이면_400이고_저장하지_않는다() throws Exception {
        mockMvc.perform(post("/internal/payments")
                        .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "trade_id", 123, "amount", 0, "order_name", "상품"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));

        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void 주문명이_101자면_400이고_저장하지_않는다() throws Exception {
        mockMvc.perform(post("/internal/payments")
                        .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "trade_id", 123, "amount", 1000, "order_name", "가".repeat(101)))))
                .andExpect(status().isBadRequest());

        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void 잘못된_JSON의_원문을_로그에_남기지_않는다() throws Exception {
        try (LogCapture logs = LogCapture.at(GlobalExceptionHandler.class, Level.DEBUG)) {
            mockMvc.perform(post("/internal/payments")
                            .header(INTERNAL_KEY_HEADER, INTERNAL_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"secret-marker\":"))
                    .andExpect(status().isBadRequest());

            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("reason="));
        }
    }

    @Test
    void 상태를_조회하면_실제_상태를_반환한다() throws Exception {
        Payment payment = paymentRepository.save(Payment.ready(7L, 3000L, "상품"));

        mockMvc.perform(get("/internal/payments/{orderId}", payment.getOrderId())
                        .header(INTERNAL_KEY_HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.trade_id").value(7))
                .andExpect(jsonPath("$.data.order_id").value(payment.getOrderId()));
    }

    @Test
    void 없는_order_id를_조회하면_404다() throws Exception {
        mockMvc.perform(get("/internal/payments/{orderId}", "IGN-none")
                        .header(INTERNAL_KEY_HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
