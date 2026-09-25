package io.wisoft.ignoa_payment.global.exception;

public record ErrorDetail(
        String field,
        String reason
) {
}
