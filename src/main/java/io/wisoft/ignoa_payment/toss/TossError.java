package io.wisoft.ignoa_payment.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossError(
        String code,
        String message
) {
    static final String UNREADABLE_CODE = "UNREADABLE_ERROR";

    static TossError unreadable(int httpStatus) {
        return new TossError(UNREADABLE_CODE, "Toss 에러 응답을 읽을 수 없습니다: HTTP " + httpStatus);
    }
}
