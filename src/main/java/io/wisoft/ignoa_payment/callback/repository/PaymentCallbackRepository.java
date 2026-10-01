package io.wisoft.ignoa_payment.callback.repository;

import io.wisoft.ignoa_payment.callback.entity.CallbackStatus;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentCallbackRepository extends JpaRepository<PaymentCallback, Long> {

    List<PaymentCallback> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            CallbackStatus status, LocalDateTime now, Pageable pageable);
}
