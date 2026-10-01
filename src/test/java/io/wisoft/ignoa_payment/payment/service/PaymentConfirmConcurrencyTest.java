package io.wisoft.ignoa_payment.payment.service;

import io.wisoft.ignoa_payment.payment.dto.PaymentConfirmRequest;
import io.wisoft.ignoa_payment.payment.dto.PaymentResultResponse;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import io.wisoft.ignoa_payment.toss.TossConfirmResult;
import io.wisoft.ignoa_payment.toss.TossPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 실제 스레드로 동시에 승인을 요청해 조건부 UPDATE와 유니크 제약이 경합을 막는지 확인한다.
class PaymentConfirmConcurrencyTest extends IntegrationTestSupport {

    private static final long AMOUNT = 1000L;
    private static final long TOSS_DELAY_MILLIS = 200L; // 경합 구간을 넓히려고 Toss 응답을 늦춘다

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    private static TossConfirmResult done(String paymentKey, String orderId) throws InterruptedException {
        Thread.sleep(TOSS_DELAY_MILLIS);
        return new TossConfirmResult.Responded(new TossPayment(paymentKey, orderId, "DONE", AMOUNT, null));
    }

    // 모든 작업을 같은 순간에 출발시키고 결과를 모은다.
    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 같은_주문을_동시에_10번_승인해도_Toss는_한_번만_호출되고_DONE은_한_건이다() throws Exception {
        Payment payment = paymentService.prepare(1L, AMOUNT, "상품");
        given(tossClient.confirm("pk", payment.getOrderId(), AMOUNT))
                .willAnswer(invocation -> done("pk", payment.getOrderId()));
        PaymentConfirmRequest request = new PaymentConfirmRequest(1L, payment.getOrderId(), "pk", AMOUNT);

        List<Callable<PaymentResultResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> paymentFacade.confirm(request));
        }
        List<PaymentResultResponse> results = runConcurrently(tasks);

        verify(tossClient, times(1)).confirm(anyString(), anyString(), anyLong());
        // 먼저 승인을 시작한 요청은 DONE, 그 사이 들어온 요청은 UNKNOWN(승인 중) 또는 DONE(이미 끝남)
        assertThat(results).extracting(PaymentResultResponse::status).containsOnly("DONE", "UNKNOWN").contains("DONE");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    void 같은_trade의_서로_다른_주문을_동시에_승인해도_성공_결제는_한_건이다() throws Exception {
        long tradeId = 7L;
        List<Payment> payments = new ArrayList<>();
        List<Callable<PaymentResultResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Payment payment = paymentService.prepare(tradeId, AMOUNT, "상품");
            String paymentKey = "pk-" + i;
            payments.add(payment);
            given(tossClient.confirm(paymentKey, payment.getOrderId(), AMOUNT))
                    .willAnswer(invocation -> done(paymentKey, payment.getOrderId()));
            tasks.add(() -> paymentFacade.confirm(
                    new PaymentConfirmRequest(tradeId, payment.getOrderId(), paymentKey, AMOUNT)));
        }
        given(tossClient.cancel(anyString(), anyString())).willReturn(true);

        runConcurrently(tasks);

        // paid_trade_id 유니크 제약이 최후 방어선: Toss가 모두 승인해도 DONE은 하나뿐이고 나머지는 취소·실패 처리된다.
        List<PaymentStatus> statuses = payments.stream()
                .map(p -> paymentRepository.findById(p.getId()).orElseThrow().getStatus())
                .toList();
        assertThat(statuses).filteredOn(s -> s == PaymentStatus.DONE).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == PaymentStatus.FAILED).hasSize(4);
    }
}
