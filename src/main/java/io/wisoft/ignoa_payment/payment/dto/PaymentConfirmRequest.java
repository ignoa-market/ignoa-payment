package io.wisoft.ignoa_payment.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentConfirmRequest(
        @NotNull @Positive
        Long tradeId,

        @NotBlank @Size(max = 64)
        String orderId,

        @NotBlank @Size(max = 200)
        String paymentKey,

        @NotNull @Min(1)
        Long amount
) {
}
