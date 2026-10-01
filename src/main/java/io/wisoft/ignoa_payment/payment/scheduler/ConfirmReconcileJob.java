package io.wisoft.ignoa_payment.payment.scheduler;

import io.wisoft.ignoa_payment.global.metrics.PaymentMetrics;
import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import io.wisoft.ignoa_payment.payment.service.PaymentService;
import io.wisoft.ignoa_payment.payment.service.TossResultApplier;
import io.wisoft.ignoa_payment.toss.TossClient;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// 승인 결과를 모르는(CONFIRMING) 결제를 Toss에 다시 물어 결론 낸다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmReconcileJob {

    private static final int BATCH_SIZE = 100;
    private static final Duration RECONCILE_DELAY = Duration.ofMinutes(1);
    // Toss 결제는 30분이 지나면 만료(EXPIRED)되어 이후 승인될 수 없다.
    private static final Duration EXPIRE_AFTER = Duration.ofMinutes(30);

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final TossResultApplier tossResultApplier;
    private final TossClient tossClient;
    private final PaymentMetrics paymentMetrics;

    public void execute(LocalDateTime now) {
        reconcileConfirming(now);
        expireStaleReady(now);
    }

    // READY는 Toss 승인을 요청한 적이 없어 돈이 나가지 않았다. 30분이 지나면 결제창 인증도 만료되므로
    // 실패로 확정해 콜백을 보낸다(승인 요청이 커밋 전 5xx로 끝나 api가 결론을 기다리는 경우 대비).
    private void expireStaleReady(LocalDateTime now) {
        List<Payment> staleReady = paymentRepository.findByStatusAndCreatedAtBefore(
                PaymentStatus.READY, now.minus(EXPIRE_AFTER), PageRequest.of(0, BATCH_SIZE, Sort.by("id")));
        for (Payment payment : staleReady) {
            paymentService.markFailed(payment.getId(), FailureCode.EXPIRED,
                    "결제가 진행되지 않아 만료 처리했습니다.", null, now);
        }
        if (!staleReady.isEmpty()) {
            log.info("방치된 결제 준비 건 만료 처리 완료: target={}", staleReady.size());
        }
    }

    private void reconcileConfirming(LocalDateTime now) {
        List<Payment> targets = paymentRepository.findByStatusAndConfirmRequestedAtBefore(
                PaymentStatus.CONFIRMING, now.minus(RECONCILE_DELAY),
                PageRequest.of(0, BATCH_SIZE, Sort.by("id")));
        if (targets.isEmpty()) {
            return;
        }

        int lookupFailed = 0;
        int errors = 0;
        for (Payment payment : targets) {
            // 한 건의 예외가 이후 건(다음 회차에도 같은 순서)을 막지 않게 건별로 격리한다.
            try {
                if (!reconcileOne(payment, now)) {
                    lookupFailed++;
                }
            } catch (RuntimeException e) {
                errors++;
                paymentMetrics.recordReconcile("ERROR");
                log.error("미확정 결제 재조회 중 예외: orderId={}", payment.getOrderId(), e);
            }
        }
        log.info("미확정 결제 재조회 완료: target={}, lookupFailed={}, errors={}", targets.size(), lookupFailed, errors);
    }

    // 조회에 실패하면 false
    private boolean reconcileOne(Payment payment, LocalDateTime now) {
        TossLookupResult lookup = tossClient.getByOrderId(payment.getOrderId());

        if (lookup instanceof TossLookupResult.Failed failed) {
            paymentMetrics.recordReconcile("LOOKUP_FAILED");
            log.warn("미확정 결제 재조회 실패: orderId={}, reason={}", payment.getOrderId(), failed.reason());
            return false;
        }
        if (lookup instanceof TossLookupResult.Found found) {
            tossResultApplier.apply(payment.getId(), found.payment(), now);
        }
        expireIfTooOld(payment, now);
        paymentMetrics.recordReconcile(paymentRepository.findById(payment.getId())
                .map(p -> p.getStatus().name())
                .orElse("MISSING"));
        return true;
    }

    // 이미 결론 난 결제는 markFailed의 조건부 UPDATE가 0건으로 끝난다.
    private void expireIfTooOld(Payment payment, LocalDateTime now) {
        if (payment.getConfirmRequestedAt().plus(EXPIRE_AFTER).isAfter(now)) {
            return;
        }
        if (paymentService.markFailed(payment.getId(), FailureCode.EXPIRED,
                "승인 결과를 확인할 수 없어 만료 처리했습니다.", null, now)) {
            log.warn("미확정 결제 만료 처리: tradeId={}, orderId={}", payment.getTradeId(), payment.getOrderId());
        }
    }
}
