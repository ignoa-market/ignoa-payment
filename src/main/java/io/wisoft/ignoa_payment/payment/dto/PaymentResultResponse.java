package io.wisoft.ignoa_payment.payment.dto;

import io.wisoft.ignoa_payment.payment.entity.FailureCode;
import io.wisoft.ignoa_payment.payment.entity.Payment;

import java.time.LocalDateTime;

public record PaymentResultResponse(
        Long tradeId,
        String orderId,
        String status,
        Long amount,
        LocalDateTime approvedAt,
        FailureCode failureCode,
        String failureMessage
) {
    private static final String UNKNOWN = "UNKNOWN";

    // 상태 조회·콜백용: 실제 상태 그대로
    public static PaymentResultResponse from(Payment payment) {
        return of(payment, payment.getStatus().name());
    }

    // 승인 응답용: 결론이 나지 않은 상태는 UNKNOWN으로 알린다(계약 4.2)
    public static PaymentResultResponse forConfirm(Payment payment) {
        String status = switch (payment.getStatus()) {
            case READY, CONFIRMING -> UNKNOWN;
            default -> payment.getStatus().name();
        };
        return of(payment, status);
    }

    private static PaymentResultResponse of(Payment payment, String status) {
        return new PaymentResultResponse(
                payment.getTradeId(),
                payment.getOrderId(),
                status,
                payment.getAmount(),
                payment.getApprovedAt(),
                payment.getFailureCode(),
                payment.getFailureMessage()
        );
    }
}
