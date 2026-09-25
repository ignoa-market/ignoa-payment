package io.wisoft.ignoa_payment.payment.repository;

import io.wisoft.ignoa_payment.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);
}
