package io.wisoft.ignoa_payment.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentPrepareRequest(
        @NotNull @Positive
        Long tradeId,

        @NotNull @Min(1)
        Long amount,

        // Toss orderName 최대 100자
        @NotBlank @Size(max = 100)
        String orderName
) {
}
