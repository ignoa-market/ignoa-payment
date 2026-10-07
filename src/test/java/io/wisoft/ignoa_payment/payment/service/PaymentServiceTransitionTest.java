package io.wisoft.ignoa_payment.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wisoft.ignoa_payment.callback.entity.CallbackStatus;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.support.LogCapture;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTransitionTest extends IntegrationTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 12, 0, 0);
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 9, 25, 12, 0, 5);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReader paymentReader;

    @Autowired
    private PaymentCallbackRepository callbackRepository;

    @Test
    void READY에서만_승인을_시작할_수_있다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");

        assertThat(paymentService.startConfirm(payment.getId(), "pk-1", NOW)).isTrue();
        assertThat(paymentService.startConfirm(payment.getId(), "pk-1", NOW)).isFalse();

        Payment found = paymentReader.getById(payment.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.CONFIRMING);
        assertThat(found.getPaymentKey()).isEqualTo("pk-1");
        assertThat(found.getConfirmRequestedAt()).isEqualTo(NOW);
    }

    @Test
    void DONE으로_바꾸면_paid_trade_id를_채우고_콜백을_한_건_적재한다() throws Exception {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk-1", NOW);

        assertThat(paymentService.markDone(payment.getId(), "pk-1", APPROVED_AT, "DONE", NOW)).isTrue();
        assertThat(paymentService.markDone(payment.getId(), "pk-1", APPROVED_AT, "DONE", NOW)).isFalse();

        Payment found = paymentReader.getById(payment.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(found.getPaidTradeId()).isEqualTo(1L);
        assertThat(found.getApprovedAt()).isEqualTo(APPROVED_AT);

        PaymentCallback callback = callbackRepository.findAll().getFirst();
        assertThat(callbackRepository.count()).isEqualTo(1);
        assertThat(callback.getStatus()).isEqualTo(CallbackStatus.PENDING);
        assertThat(callback.getTradeId()).isEqualTo(1L);
        assertThat(callback.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(callback.getFirstEnqueuedAt()).isEqualTo(NOW);

        JsonNode payload = objectMapper.readTree(callback.getPayload());
        assertThat(payload.get("trade_id").asLong()).isEqualTo(1L);
        assertThat(payload.get("order_id").asText()).isEqualTo(payment.getOrderId());
        assertThat(payload.get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void 정상_결제_승인은_INFO_로그를_남기지_않는다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk-1", NOW);

        try (LogCapture logs = LogCapture.at(PaymentService.class, Level.DEBUG)) {
            paymentService.markDone(payment.getId(), "pk-1", APPROVED_AT, "DONE", NOW);

            assertThat(logs.events()).noneMatch(event ->
                    event.getLevel().isGreaterOrEqual(Level.INFO)
                            && event.getFormattedMessage().contains("결제 승인 완료"));
        }
    }

    @Test
    void 같은_trade에_두_번째_DONE은_유니크_제약으로_실패한다() {
        Payment first = paymentService.prepare(1L, 1000L, "상품");
        Payment second = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markDone(first.getId(), "pk-1", APPROVED_AT, "DONE", NOW);

        assertThatThrownBy(() -> paymentService.markDone(second.getId(), "pk-2", APPROVED_AT, "DONE", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(paymentReader.getById(second.getId()).getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(callbackRepository.count()).isEqualTo(1);
    }

    @Test
    void FAILED로_바꾸면_사유를_남기고_콜백을_적재한다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");

        assertThat(paymentService.markFailed(payment.getId(), FailureCode.AMOUNT_MISMATCH, "금액 불일치", null, NOW)).isTrue();

        Payment found = paymentReader.getById(payment.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(found.getFailureCode()).isEqualTo(FailureCode.AMOUNT_MISMATCH);
        assertThat(found.getFailureMessage()).isEqualTo("금액 불일치");
        assertThat(callbackRepository.count()).isEqualTo(1);
    }

    @Test
    void DONE인_결제는_FAILED로_바꿀_수_없다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markDone(payment.getId(), "pk-1", APPROVED_AT, "DONE", NOW);

        assertThat(paymentService.markFailed(payment.getId(), FailureCode.EXPIRED, "만료", null, NOW)).isFalse();
        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void 실패_사유가_500자를_넘으면_잘라서_저장한다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");

        paymentService.markFailed(payment.getId(), FailureCode.TOSS_REJECTED, "가".repeat(600), null, NOW);

        assertThat(paymentReader.getById(payment.getId()).getFailureMessage()).hasSize(500);
    }

    @Test
    void CANCELED는_DONE에서만_가능하고_paid_trade_id를_비우며_콜백은_없다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        assertThat(paymentService.markCanceled(payment.getId(), "CANCELED")).isFalse();

        paymentService.markDone(payment.getId(), "pk-1", APPROVED_AT, "DONE", NOW);
        assertThat(paymentService.markCanceled(payment.getId(), "CANCELED")).isTrue();

        Payment found = paymentReader.getById(payment.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(found.getPaidTradeId()).isNull();
        assertThat(callbackRepository.count()).isEqualTo(1); // DONE 때 적재된 1건뿐
    }
}
