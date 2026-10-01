package io.wisoft.ignoa_payment.global.common;

public record ApiResponse<T>(
        T data,
        String message
) {
    public static <T> ApiResponse<T> of(T data, String message) {
        return new ApiResponse<>(data, message);
    }
}
