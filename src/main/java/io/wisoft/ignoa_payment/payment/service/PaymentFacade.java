package io.wisoft.ignoa_payment.payment.service;

import io.wisoft.ignoa_payment.global.exception.BusinessException;
import io.wisoft.ignoa_payment.global.exception.ErrorCode;
import io.wisoft.ignoa_payment.global.metrics.PaymentMetrics;
import io.wisoft.ignoa_payment.payment.dto.PaymentConfirmRequest;
import io.wisoft.ignoa_payment.payment.dto.PaymentResultResponse;
import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import io.wisoft.ignoa_payment.toss.TossClient;
import io.wisoft.ignoa_payment.toss.TossConfirmResult;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

// 트랜잭션을 열지 않는다. Toss 호출은 DB 트랜잭션 밖에서 하고, 상태 변경은 PaymentService의 짧은 트랜잭션으로 한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentFacade {

    private final PaymentReader paymentReader;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final TossResultApplier tossResultApplier;
    private final TossClient tossClient;
    private final PaymentMetrics paymentMetrics;

    public PaymentResultResponse confirm(PaymentConfirmRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Payment payment = paymentReader.getByOrderId(request.orderId());

        validateOwnership(payment, request);

        if (payment.getStatus() != PaymentStatus.READY) {
            PaymentResultResponse response = PaymentResultResponse.forConfirm(payment);
            paymentMetrics.recordConfirm(response.status());
            return response;
        }

        if (!payment.getAmount().equals(request.amount())) {
            paymentService.markFailed(payment.getId(), FailureCode.AMOUNT_MISMATCH,
                    "요청 금액이 준비된 금액과 다릅니다.", null, now);
            return reload(payment);
        }

        if (paymentRepository.existsByTradeIdAndStatus(payment.getTradeId(), PaymentStatus.DONE)) {
            paymentService.markFailed(payment.getId(), FailureCode.ALREADY_PAID,
                    "같은 주문에 이미 성공한 결제가 있습니다.", null, now);
            return reload(payment);
        }

        if (!startConfirm(payment, request.paymentKey(), now)) {
            // 다른 요청이 먼저 승인을 시작했다.
            return reload(payment);
        }

        TossConfirmResult result = tossClient.confirm(request.paymentKey(), payment.getOrderId(), payment.getAmount());
        applyConfirmResult(payment, result, now);
        return reload(payment);
    }

    // payment_key는 결제 건마다 유일하다. 다른 주문에 이미 쓰인 키면 500이 아니라 409로 알려
    // api가 CONFIRMING을 되돌리게 한다(5xx는 UNKNOWN으로 취급되어 결론이 나지 않는다).
    private boolean startConfirm(Payment payment, String paymentKey, LocalDateTime now) {
        try {
            return paymentService.startConfirm(payment.getId(), paymentKey, now);
        } catch (DataIntegrityViolationException e) {
            log.debug("다른 주문에 쓰인 결제 키로 승인 요청: orderId={}", payment.getOrderId());
            throw new BusinessException(ErrorCode.PAYMENT_KEY_MISMATCH);
        }
    }

    private void validateOwnership(Payment payment, PaymentConfirmRequest request) {
        if (!payment.getTradeId().equals(request.tradeId())) {
            throw new BusinessException(ErrorCode.PAYMENT_TRADE_MISMATCH);
        }
        if (payment.getPaymentKey() != null && !payment.getPaymentKey().equals(request.paymentKey())) {
            throw new BusinessException(ErrorCode.PAYMENT_KEY_MISMATCH);
        }
    }

    private void applyConfirmResult(Payment payment, TossConfirmResult result, LocalDateTime now) {
        switch (result) {
            case TossConfirmResult.Responded responded ->
                    tossResultApplier.apply(payment.getId(), responded.payment(), now);
            case TossConfirmResult.Rejected rejected -> paymentService.markFailed(payment.getId(),
                    FailureCode.TOSS_REJECTED, rejected.code() + ": " + rejected.message(), null, now);
            case TossConfirmResult.SessionExpired expired -> paymentService.markFailed(payment.getId(),
                    FailureCode.EXPIRED, "결제 인증 후 승인 가능 시간이 지났습니다.", null, now);
            case TossConfirmResult.AlreadyProcessed alreadyProcessed -> lookupAndApply(payment, now);
            case TossConfirmResult.Unknown unknown -> log.warn(
                    "결제 승인 결과 미확정: tradeId={}, orderId={}, reason={}, action=재조회 대기",
                    payment.getTradeId(), payment.getOrderId(), unknown.reason());
        }
    }

    private void lookupAndApply(Payment payment, LocalDateTime now) {
        TossLookupResult lookup = tossClient.getByOrderId(payment.getOrderId());
        if (lookup instanceof TossLookupResult.Found found) {
            tossResultApplier.apply(payment.getId(), found.payment(), now);
            return;
        }
        log.warn("이미 처리된 결제 조회 실패: orderId={}, result={}, action=재조회 대기", payment.getOrderId(), lookup);
    }

    private PaymentResultResponse reload(Payment payment) {
        PaymentResultResponse response = PaymentResultResponse.forConfirm(paymentReader.getById(payment.getId()));
        paymentMetrics.recordConfirm(response.status());
        return response;
    }
}
