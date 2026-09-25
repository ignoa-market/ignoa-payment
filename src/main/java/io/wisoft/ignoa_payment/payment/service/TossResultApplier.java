package io.wisoft.ignoa_payment.payment.service;

import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.toss.TossClient;
import io.wisoft.ignoa_payment.toss.TossPayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// Toss가 알려준 결제 상태를 우리 결제 상태로 옮긴다. 승인 응답·재조회·웹훅이 모두 이 규칙을 쓴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class TossResultApplier {

    private final PaymentService paymentService;
    private final PaymentReader paymentReader;
    private final TossClient tossClient;

    public void apply(Long paymentId, TossPayment tossPayment, LocalDateTime now) {
        String tossStatus = tossPayment.status();
        switch (tossStatus) {
            case "DONE" -> applyDone(paymentId, tossPayment, now);
            case "ABORTED" -> paymentService.markFailed(
                    paymentId, FailureCode.TOSS_REJECTED, "Toss 승인에 실패했습니다.", tossStatus, now);
            case "EXPIRED" -> paymentService.markFailed(
                    paymentId, FailureCode.EXPIRED, "결제 유효 시간이 지났습니다.", tossStatus, now);
            case "CANCELED", "PARTIAL_CANCELED" -> applyCanceled(paymentId, tossStatus, now);
            case "WAITING_FOR_DEPOSIT" -> applyUnsupportedMethod(paymentId, tossPayment, now);
            default -> log.debug("결제 상태 반영 생략: paymentId={}, tossStatus={}, reason=진행 중 상태",
                    paymentId, tossStatus);
        }
    }

    private void applyDone(Long paymentId, TossPayment tossPayment, LocalDateTime now) {
        try {
            paymentService.markDone(paymentId, tossPayment.paymentKey(),
                    tossPayment.approvedAtInSeoul(), tossPayment.status(), now);
        } catch (DataIntegrityViolationException e) {
            // 같은 trade에 이미 성공 결제가 있다. api의 CONFIRMING 가드가 있어 사실상 일어나지 않는 최후 방어선.
            boolean canceled = tossClient.cancel(tossPayment.paymentKey(), "동일 주문 중복 결제 자동 취소");
            if (canceled) {
                log.error("중복 결제 자동 취소 완료: paymentId={}, paymentKey={}", paymentId, tossPayment.paymentKey());
            } else {
                log.error("중복 결제 자동 취소 실패: paymentId={}, paymentKey={}, action=Toss 관리자에서 수동 취소",
                        paymentId, tossPayment.paymentKey());
            }
            paymentService.markFailed(paymentId, FailureCode.ALREADY_PAID,
                    canceled ? "같은 주문에 이미 성공한 결제가 있어 자동 취소했습니다."
                            : "같은 주문에 이미 성공한 결제가 있습니다. 수동 취소가 필요합니다.",
                    canceled ? "CANCELED" : tossPayment.status(), now);
        }
    }

    private void applyCanceled(Long paymentId, String tossStatus, LocalDateTime now) {
        Payment payment = paymentReader.getById(paymentId);
        if (payment.getStatus() == PaymentStatus.DONE) {
            paymentService.markCanceled(paymentId, tossStatus);
            log.warn("승인된 결제가 Toss에서 취소됨: paymentId={}, tradeId={}, tossStatus={}",
                    paymentId, payment.getTradeId(), tossStatus);
            return;
        }
        paymentService.markFailed(paymentId, FailureCode.TOSS_REJECTED, "Toss에서 취소된 결제입니다.", tossStatus, now);
    }

    private void applyUnsupportedMethod(Long paymentId, TossPayment tossPayment, LocalDateTime now) {
        // 가상계좌는 범위 밖이다. 발급된 계좌를 취소해 입금이 들어오지 않게 한다.
        boolean canceled = tossClient.cancel(tossPayment.paymentKey(), "지원하지 않는 결제수단");
        log.error("지원하지 않는 결제수단 승인: paymentId={}, tossStatus={}, canceled={}",
                paymentId, tossPayment.status(), canceled);
        paymentService.markFailed(paymentId, FailureCode.TOSS_REJECTED,
                "지원하지 않는 결제수단입니다.", tossPayment.status(), now);
    }
}
