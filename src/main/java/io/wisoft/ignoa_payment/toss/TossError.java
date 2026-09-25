package io.wisoft.ignoa_payment.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossError(
        String code,
        String message
) {
    static TossError unreadable(int httpStatus) {
        return new TossError("UNREADABLE_ERROR", "Toss 에러 응답을 읽을 수 없습니다: HTTP " + httpStatus);
    }
}
