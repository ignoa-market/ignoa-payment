package io.wisoft.ignoa_payment.payment.entity;

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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment", indexes = {
        @Index(name = "idx_payment_trade_id", columnList = "trade_id"),
        @Index(name = "idx_payment_status_confirm_requested_at", columnList = "status, confirm_requested_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    private static final String ORDER_ID_PREFIX = "IGN-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    private String orderId;

    // ignoa-api의 주문 ID. 다른 DB이므로 FK 없이 값만 보관한다.
    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    @Column(name = "payment_key", unique = true, length = 200)
    private String paymentKey;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "order_name", nullable = false, length = 100)
    private String orderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    // DONE일 때만 trade_id를 채운다. 유니크 제약으로 trade당 성공 결제 1건을 DB가 보장한다.
    @Column(name = "paid_trade_id", unique = true)
    private Long paidTradeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 30)
    private FailureCode failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "toss_status", length = 30)
    private String tossStatus;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "confirm_requested_at")
    private LocalDateTime confirmRequestedAt;

    @Version
    private Long version;

    public static Payment ready(Long tradeId, Long amount, String orderName) {
        Payment payment = new Payment();
        payment.orderId = ORDER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        payment.tradeId = tradeId;
        payment.amount = amount;
        payment.orderName = orderName;
        payment.status = PaymentStatus.READY;
        return payment;
    }
}
