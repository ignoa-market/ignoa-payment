package io.wisoft.ignoa_payment.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<ErrorDetail> details
) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<ErrorDetail> details) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), details);
    }
}
