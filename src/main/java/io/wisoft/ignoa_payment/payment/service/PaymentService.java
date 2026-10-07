package io.wisoft.ignoa_payment.payment.service;

import io.wisoft.ignoa_payment.callback.service.CallbackAppender;
import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 500;

    private final PaymentRepository paymentRepository;
    private final PaymentReader paymentReader;
    private final CallbackAppender callbackAppender;

    public Payment prepare(Long tradeId, Long amount, String orderName) {
        Payment payment = paymentRepository.save(Payment.ready(tradeId, amount, orderName));
        log.debug("결제 준비 완료: tradeId={}, orderId={}, amount={}", tradeId, payment.getOrderId(), amount);
        return payment;
    }

    public boolean startConfirm(Long paymentId, String paymentKey, LocalDateTime now) {
        return paymentRepository.startConfirmIfReady(paymentId, paymentKey, now) == 1;
    }

    public boolean markDone(Long paymentId, String paymentKey, LocalDateTime approvedAt,
                            String tossStatus, LocalDateTime now) {
        if (paymentRepository.markDoneIfPending(paymentId, paymentKey, approvedAt, tossStatus) == 0) {
            return false;
        }
        Payment payment = paymentReader.getById(paymentId);
        callbackAppender.append(payment, now);
        log.debug("결제 승인 완료: tradeId={}, orderId={}, amount={}",
                payment.getTradeId(), payment.getOrderId(), payment.getAmount());
        return true;
    }

    public boolean markFailed(Long paymentId, FailureCode failureCode, String failureMessage,
                              String tossStatus, LocalDateTime now) {
        if (paymentRepository.markFailedIfPending(
                paymentId, failureCode, truncate(failureMessage), tossStatus) == 0) {
            return false;
        }
        Payment payment = paymentReader.getById(paymentId);
        callbackAppender.append(payment, now);
        log.debug("결제 실패 확정: tradeId={}, orderId={}, failureCode={}",
                payment.getTradeId(), payment.getOrderId(), failureCode);
        return true;
    }

    // 환불 흐름은 범위 밖이라 api에 알리지 않는다(계약 4.3).
    public boolean markCanceled(Long paymentId, String tossStatus) {
        return paymentRepository.markCanceledIfDone(paymentId, tossStatus) == 1;
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_FAILURE_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_FAILURE_MESSAGE_LENGTH);
    }
}
