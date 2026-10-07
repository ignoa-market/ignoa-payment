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
        // 상태가 비어 있으면 결론을 낼 수 없으므로 진행 중으로 보고 넘긴다.
        String tossStatus = tossPayment.status() == null ? "UNKNOWN" : tossPayment.status();
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
            if (!paymentService.markDone(paymentId, tossPayment.paymentKey(),
                    tossPayment.approvedAtInSeoul(), tossPayment.status(), now)) {
                cancelIfRecordedFailed(paymentId, tossPayment);
            }
        } catch (DataIntegrityViolationException e) {
            // 같은 trade에 이미 성공 결제가 있다. api의 CONFIRMING 가드가 있어 사실상 일어나지 않는 최후 방어선.
            boolean canceled = tossClient.cancel(tossPayment.paymentKey(), "동일 주문 중복 결제 자동 취소");
            if (canceled) {
                log.warn("중복 결제 자동 취소 완료: paymentId={}", paymentId);
            } else {
                log.error("중복 결제 자동 취소 실패: paymentId={}, action=Toss 관리자에서 수동 취소", paymentId);
            }
            paymentService.markFailed(paymentId, FailureCode.ALREADY_PAID,
                    canceled ? "같은 주문에 이미 성공한 결제가 있어 자동 취소했습니다."
                            : "같은 주문에 이미 성공한 결제가 있습니다. 수동 취소가 필요합니다.",
                    canceled ? "CANCELED" : tossPayment.status(), now);
        }
    }

    // 이미 FAILED로 api에 알린 결제가 Toss에서는 DONE이다. api는 거래를 되돌렸으므로 돈만 나간 상태로 두지 않는다.
    private void cancelIfRecordedFailed(Long paymentId, TossPayment tossPayment) {
        Payment payment = paymentReader.getById(paymentId);
        if (payment.getStatus() != PaymentStatus.FAILED) {
            return;
        }
        boolean canceled = tossClient.cancel(tossPayment.paymentKey(), "실패 처리된 결제의 승인 확인으로 자동 취소");
        if (canceled) {
            log.warn("실패 처리된 결제가 승인되어 자동 취소 완료: paymentId={}, tradeId={}",
                    paymentId, payment.getTradeId());
        } else {
            log.error("실패 처리된 결제가 승인됨, 자동 취소 실패: paymentId={}, tradeId={}, action=Toss 관리자에서 수동 취소",
                    paymentId, payment.getTradeId());
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
        if (canceled) {
            log.warn("지원하지 않는 결제수단 자동 취소 완료: paymentId={}, tossStatus={}",
                    paymentId, tossPayment.status());
        } else {
            log.error("지원하지 않는 결제수단 자동 취소 실패: paymentId={}, tossStatus={}, action=Toss 관리자에서 수동 확인",
                    paymentId, tossPayment.status());
        }
        paymentService.markFailed(paymentId, FailureCode.TOSS_REJECTED,
                "지원하지 않는 결제수단입니다.", tossPayment.status(), now);
    }
}
