package io.wisoft.ignoa_payment.callback.scheduler;

import io.wisoft.ignoa_payment.callback.client.ApiCallbackClient;
import io.wisoft.ignoa_payment.callback.entity.CallbackStatus;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import io.wisoft.ignoa_payment.callback.service.CallbackOutcome;
import io.wisoft.ignoa_payment.callback.service.CallbackService;
import io.wisoft.ignoa_payment.global.metrics.PaymentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackDispatchJob {

    private static final int BATCH_SIZE = 100;

    private final PaymentCallbackRepository callbackRepository;
    private final ApiCallbackClient apiCallbackClient;
    private final CallbackService callbackService;
    private final PaymentMetrics paymentMetrics;

    // HTTP 호출은 트랜잭션 밖에서 하고, 결과 기록만 CallbackService의 트랜잭션으로 한다.
    public void execute(LocalDateTime now) {
        List<PaymentCallback> due = callbackRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                CallbackStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE));
        if (due.isEmpty()) {
            return;
        }

        int sent = 0;
        int errors = 0;
        for (PaymentCallback callback : due) {
            // 한 건의 예외가 이후 건을 막지 않게 건별로 격리한다. 실패한 건은 PENDING으로 남아 다음 주기에 다시 보낸다.
            String stage = "SEND";
            try {
                CallbackOutcome outcome = apiCallbackClient.send(callback.getTradeId(), callback.getPayload());
                stage = "RECORD";
                CallbackStatus status = callbackService.record(callback.getId(), outcome, now);
                stage = "METRICS";
                paymentMetrics.recordCallback(status);
                if (status == CallbackStatus.SENT) {
                    sent++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("결제 결과 콜백 발송 중 예외: callbackId={}, tradeId={}, stage={}, reason={}",
                        callback.getId(), callback.getTradeId(), stage, e.getClass().getSimpleName());
            }
        }
        log.info("결제 결과 콜백 발송 완료: target={}, sent={}, notSent={}, errors={}",
                due.size(), sent, due.size() - sent, errors);
    }
}
