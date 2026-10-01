package io.wisoft.ignoa_payment.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    INVALID_JSON_FORMAT(HttpStatus.BAD_REQUEST, "JSON 형식이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // Auth
    INVALID_INTERNAL_API_KEY(HttpStatus.UNAUTHORIZED, "내부 API 키가 올바르지 않습니다."),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_TRADE_MISMATCH(HttpStatus.CONFLICT, "주문 정보가 결제와 일치하지 않습니다."),
    PAYMENT_KEY_MISMATCH(HttpStatus.CONFLICT, "결제 키가 기존 승인 요청과 일치하지 않습니다."),

    // Webhook
    WEBHOOK_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "웹훅 처리에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
