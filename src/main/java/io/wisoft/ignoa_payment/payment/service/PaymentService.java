package io.wisoft.ignoa_payment.payment.service;

import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public Payment prepare(Long tradeId, Long amount, String orderName) {
        Payment payment = paymentRepository.save(Payment.ready(tradeId, amount, orderName));
        log.debug("결제 준비 완료: tradeId={}, orderId={}, amount={}", tradeId, payment.getOrderId(), amount);
        return payment;
    }
}
