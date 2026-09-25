package io.wisoft.ignoa_payment.callback.entity;

import io.wisoft.ignoa_payment.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// ignoa-api로 보낼 결제 결과. 결제 상태 변경과 같은 트랜잭션에 적재해 유실을 막는다.
@Entity
@Table(name = "payment_callback", indexes = {
        @Index(name = "idx_payment_callback_status_next_attempt_at", columnList = "status, next_attempt_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCallback extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CallbackStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "first_enqueued_at", nullable = false)
    private LocalDateTime firstEnqueuedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    public static PaymentCallback pending(Long paymentId, Long tradeId, String payload, LocalDateTime now) {
        PaymentCallback callback = new PaymentCallback();
        callback.paymentId = paymentId;
        callback.tradeId = tradeId;
        callback.payload = payload;
        callback.status = CallbackStatus.PENDING;
        callback.attempts = 0;
        callback.nextAttemptAt = now;
        callback.firstEnqueuedAt = now;
        return callback;
    }

    public void markSent() {
        this.attempts++;
        this.status = CallbackStatus.SENT;
        this.lastError = null;
    }

    public void markDead(String error) {
        this.attempts++;
        this.status = CallbackStatus.DEAD;
        this.lastError = truncate(error);
    }

    public void scheduleRetry(LocalDateTime nextAttemptAt, String error) {
        this.attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
