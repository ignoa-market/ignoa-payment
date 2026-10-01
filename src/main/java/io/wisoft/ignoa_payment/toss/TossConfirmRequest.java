package io.wisoft.ignoa_payment.toss;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record TossConfirmRequest(
        String paymentKey,
        String orderId,
        long amount
) {
}
