package io.wisoft.ignoa_payment.payment.service;

import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.support.LogCapture;
import io.wisoft.ignoa_payment.toss.TossPayment;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TossResultApplierTest extends IntegrationTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 12, 0, 0);

    @Autowired
    private TossResultApplier applier;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReader paymentReader;

    private static TossPayment toss(String paymentKey, String orderId, String status) {
        return new TossPayment(paymentKey, orderId, status, 1000L, null);
    }

    @Test
    void 같은_trade에_두_번째_DONE이_오면_Toss에서_취소하고_ALREADY_PAID로_실패시킨다() {
        Payment first = paymentService.prepare(1L, 1000L, "상품");
        Payment second = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markDone(first.getId(), "pk-1", NOW, "DONE", NOW);
        given(tossClient.cancel(eq("pk-2"), anyString())).willReturn(true);

        try (LogCapture logs = LogCapture.at(TossResultApplier.class, Level.DEBUG)) {
            applier.apply(second.getId(), toss("pk-2", second.getOrderId(), "DONE"), NOW);

            assertThat(logs.events()).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("중복 결제 자동 취소 완료"));
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("pk-2"));
        }

        verify(tossClient).cancel(eq("pk-2"), anyString());
        Payment found = paymentReader.getById(second.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(found.getFailureCode()).isEqualTo(FailureCode.ALREADY_PAID);
    }

    @Test
    void 중복_결제_자동_취소_실패는_ERROR로_남기되_결제_키는_남기지_않는다() {
        Payment first = paymentService.prepare(1L, 1000L, "상품");
        Payment second = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markDone(first.getId(), "pk-1", NOW, "DONE", NOW);
        given(tossClient.cancel(eq("pk-secret"), anyString())).willReturn(false);

        try (LogCapture logs = LogCapture.at(TossResultApplier.class, Level.DEBUG)) {
            applier.apply(second.getId(), toss("pk-secret", second.getOrderId(), "DONE"), NOW);

            assertThat(logs.events()).anyMatch(event -> event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().contains("중복 결제 자동 취소 실패"));
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("pk-secret"));
        }
    }

    @Test
    void 실패로_기록한_결제가_Toss에서_DONE이면_api와_어긋나지_않게_Toss에서_취소한다() {
        // api는 FAILED 콜백을 받아 거래를 되돌렸다. 돈만 나간 상태로 두지 않는다.
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markFailed(payment.getId(), FailureCode.EXPIRED, "만료", null, NOW);
        given(tossClient.cancel(eq("pk"), anyString())).willReturn(true);

        try (LogCapture logs = LogCapture.at(TossResultApplier.class, Level.DEBUG)) {
            applier.apply(payment.getId(), toss("pk", payment.getOrderId(), "DONE"), NOW);
            assertThat(logs.events()).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("자동 취소 완료"));
            assertThat(logs.events()).noneMatch(event -> event.getFormattedMessage().contains("paymentKey="));
        }

        verify(tossClient).cancel(eq("pk"), anyString());
        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void 이미_DONE인_결제에_DONE이_다시_오면_취소하지_않는다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markDone(payment.getId(), "pk", NOW, "DONE", NOW);

        applier.apply(payment.getId(), toss("pk", payment.getOrderId(), "DONE"), NOW);

        verify(tossClient, never()).cancel(anyString(), anyString());
    }

    @Test
    void 가상계좌_입금대기는_취소하고_실패시킨다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        given(tossClient.cancel(eq("pk"), anyString())).willReturn(true);

        try (LogCapture logs = LogCapture.at(TossResultApplier.class, Level.DEBUG)) {
            applier.apply(payment.getId(), toss("pk", payment.getOrderId(), "WAITING_FOR_DEPOSIT"), NOW);
            assertThat(logs.events()).anyMatch(event -> event.getLevel() == Level.WARN);
        }

        verify(tossClient).cancel(eq("pk"), anyString());
        assertThat(paymentReader.getById(payment.getId()).getFailureCode()).isEqualTo(FailureCode.TOSS_REJECTED);
    }

    @Test
    void ABORTED는_TOSS_REJECTED_EXPIRED는_EXPIRED다() {
        Payment aborted = paymentService.prepare(1L, 1000L, "상품");
        Payment expired = paymentService.prepare(2L, 1000L, "상품");

        applier.apply(aborted.getId(), toss("pk-a", aborted.getOrderId(), "ABORTED"), NOW);
        applier.apply(expired.getId(), toss("pk-e", expired.getOrderId(), "EXPIRED"), NOW);

        assertThat(paymentReader.getById(aborted.getId()).getFailureCode()).isEqualTo(FailureCode.TOSS_REJECTED);
        assertThat(paymentReader.getById(expired.getId()).getFailureCode()).isEqualTo(FailureCode.EXPIRED);
    }

    @Test
    void DONE인_결제에_CANCELED가_오면_CANCELED로_기록한다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.markDone(payment.getId(), "pk", NOW, "DONE", NOW);

        applier.apply(payment.getId(), toss("pk", payment.getOrderId(), "CANCELED"), NOW);

        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void Toss_상태가_null이면_진행_중으로_보고_예외없이_넘긴다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk", NOW);

        applier.apply(payment.getId(), toss("pk", payment.getOrderId(), null), NOW);

        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.CONFIRMING);
    }

    @Test
    void 진행_중_상태는_아무것도_바꾸지_않는다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk", NOW);

        applier.apply(payment.getId(), toss("pk", payment.getOrderId(), "IN_PROGRESS"), NOW);

        assertThat(paymentReader.getById(payment.getId()).getStatus()).isEqualTo(PaymentStatus.CONFIRMING);
    }
}
