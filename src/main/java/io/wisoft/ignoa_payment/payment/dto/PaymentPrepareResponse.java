package io.wisoft.ignoa_payment.payment.dto;

import io.wisoft.ignoa_payment.payment.entity.Payment;

public record PaymentPrepareResponse(
        String orderId,
        Long tradeId,
        Long amount,
        String orderName
) {
    public static PaymentPrepareResponse from(Payment payment) {
        return new PaymentPrepareResponse(
                payment.getOrderId(), payment.getTradeId(), payment.getAmount(), payment.getOrderName());
    }
}
