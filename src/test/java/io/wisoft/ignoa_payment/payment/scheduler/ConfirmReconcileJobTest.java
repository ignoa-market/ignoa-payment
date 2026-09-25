package io.wisoft.ignoa_payment.payment.scheduler;

import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.service.PaymentReader;
import io.wisoft.ignoa_payment.payment.service.PaymentService;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import io.wisoft.ignoa_payment.toss.TossPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ConfirmReconcileJobTest extends IntegrationTestSupport {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 9, 25, 12, 0, 0);

    @Autowired
    private ConfirmReconcileJob job;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentReader paymentReader;

    private Payment confirming() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품");
        paymentService.startConfirm(payment.getId(), "pk", REQUESTED_AT);
        return payment;
    }

    private PaymentStatus statusOf(Payment payment) {
        return paymentReader.getById(payment.getId()).getStatus();
    }

    @Test
    void 요청_후_1분이_안_지났으면_조회하지_않는다() {
        confirming();

        job.execute(REQUESTED_AT.plusSeconds(30));

        verify(tossClient, never()).getByOrderId(anyString());
    }

    @Test
    void Toss가_DONE이면_DONE으로_확정한다() {
        Payment payment = confirming();
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.Found(
                new TossPayment("pk", payment.getOrderId(), "DONE", 1000L, null)));

        job.execute(REQUESTED_AT.plusMinutes(2));

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void Toss에_기록이_없어도_30분_전이면_기다린다() {
        Payment payment = confirming();
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.NotFound());

        job.execute(REQUESTED_AT.plusMinutes(10));

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.CONFIRMING);
    }

    @Test
    void Toss에_기록이_없고_30분이_지나면_EXPIRED로_실패시킨다() {
        Payment payment = confirming();
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.NotFound());

        job.execute(REQUESTED_AT.plusMinutes(31));

        Payment found = paymentReader.getById(payment.getId());
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(found.getFailureCode()).isEqualTo(FailureCode.EXPIRED);
    }

    @Test
    void 진행_중_상태로_30분이_지나도_EXPIRED로_실패시킨다() {
        Payment payment = confirming();
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.Found(
                new TossPayment("pk", payment.getOrderId(), "IN_PROGRESS", 1000L, null)));

        job.execute(REQUESTED_AT.plusMinutes(31));

        assertThat(paymentReader.getById(payment.getId()).getFailureCode()).isEqualTo(FailureCode.EXPIRED);
    }

    @Test
    void 조회가_실패하면_30분이_지나도_상태를_바꾸지_않는다() {
        Payment payment = confirming();
        given(tossClient.getByOrderId(payment.getOrderId())).willReturn(new TossLookupResult.Failed("HTTP 503"));

        job.execute(REQUESTED_AT.plusMinutes(31));

        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.CONFIRMING);
    }

    @Test
    void CONFIRMING이_아닌_결제는_대상이_아니다() {
        Payment payment = paymentService.prepare(1L, 1000L, "상품"); // READY

        job.execute(REQUESTED_AT.plusHours(1));

        verify(tossClient, never()).getByOrderId(payment.getOrderId());
    }
}
