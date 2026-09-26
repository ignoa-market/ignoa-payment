package io.wisoft.ignoa_payment.payment.repository;

import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.entity.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);

    boolean existsByTradeIdAndStatus(Long tradeId, PaymentStatus status);

    List<Payment> findByStatusAndConfirmRequestedAtBefore(
            PaymentStatus status, LocalDateTime threshold, Pageable pageable);

    List<Payment> findByStatusAndCreatedAtBefore(
            PaymentStatus status, LocalDateTime threshold, Pageable pageable);

    // 승인 시작 조건부 UPDATE: READY일 때만 CONFIRMING
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
            SET p.status = 'CONFIRMING',
                p.paymentKey = :paymentKey,
                p.confirmRequestedAt = :now,
                p.version = p.version + 1
            WHERE p.id = :id
                AND p.status = 'READY'
            """)
    int startConfirmIfReady(@Param("id") Long id,
                            @Param("paymentKey") String paymentKey,
                            @Param("now") LocalDateTime now);

    // 승인 완료 조건부 UPDATE: paid_trade_id 유니크 제약이 trade당 성공 1건을 보장한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
            SET p.status = 'DONE',
                p.paidTradeId = p.tradeId,
                p.paymentKey = :paymentKey,
                p.approvedAt = :approvedAt,
                p.tossStatus = :tossStatus,
                p.version = p.version + 1
            WHERE p.id = :id
                AND p.status IN ('READY', 'CONFIRMING')
            """)
    int markDoneIfPending(@Param("id") Long id,
                          @Param("paymentKey") String paymentKey,
                          @Param("approvedAt") LocalDateTime approvedAt,
                          @Param("tossStatus") String tossStatus);

    // 실패 조건부 UPDATE: 결론 나지 않은 결제만
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
            SET p.status = 'FAILED',
                p.failureCode = :failureCode,
                p.failureMessage = :failureMessage,
                p.tossStatus = :tossStatus,
                p.version = p.version + 1
            WHERE p.id = :id
                AND p.status IN ('READY', 'CONFIRMING')
            """)
    int markFailedIfPending(@Param("id") Long id,
                            @Param("failureCode") FailureCode failureCode,
                            @Param("failureMessage") String failureMessage,
                            @Param("tossStatus") String tossStatus);

    // 취소 조건부 UPDATE: DONE만. paid_trade_id를 비워 유니크 제약에서 뺀다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
            SET p.status = 'CANCELED',
                p.paidTradeId = null,
                p.tossStatus = :tossStatus,
                p.version = p.version + 1
            WHERE p.id = :id
                AND p.status = 'DONE'
            """)
    int markCanceledIfDone(@Param("id") Long id,
                           @Param("tossStatus") String tossStatus);
}
