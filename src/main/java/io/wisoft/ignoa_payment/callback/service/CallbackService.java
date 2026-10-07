package io.wisoft.ignoa_payment.callback.service;

import io.wisoft.ignoa_payment.callback.entity.CallbackStatus;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CallbackService {

    private static final Duration MAX_RETRY_WINDOW = Duration.ofHours(24);
    private static final Duration MAX_DELAY = Duration.ofMinutes(30);

    private final PaymentCallbackRepository callbackRepository;

    public CallbackStatus record(Long callbackId, CallbackOutcome outcome, LocalDateTime now) {
        PaymentCallback callback = callbackRepository.findById(callbackId).orElseThrow();

        switch (outcome.type()) {
            case SUCCESS -> callback.markSent();
            case RETRYABLE -> {
                if (!now.isBefore(callback.getFirstEnqueuedAt().plus(MAX_RETRY_WINDOW))) {
                    callback.markDead(outcome.error());
                    log.error("결제 결과 콜백 재시도 기한 초과: callbackId={}, tradeId={}, attempts={}, action=GET /internal/payments로 수동 대조",
                            callbackId, callback.getTradeId(), callback.getAttempts());
                } else {
                    callback.scheduleRetry(now.plus(delayAfter(callback.getAttempts() + 1)), outcome.error());
                    log.debug("결제 결과 콜백 재시도 예약: callbackId={}, tradeId={}, attempts={}, nextAttemptAt={}",
                            callbackId, callback.getTradeId(), callback.getAttempts(), callback.getNextAttemptAt());
                }
            }
        }
        return callback.getStatus();
    }

    // n번째 실패 후 대기 시간: 1·2·4·8·16분, 이후 30분
    public static Duration delayAfter(int attempts) {
        if (attempts >= 6) {
            return MAX_DELAY;
        }
        return Duration.ofMinutes(1L << (attempts - 1));
    }
}
