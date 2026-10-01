package io.wisoft.ignoa_payment.callback.service;

public record CallbackOutcome(
        Type type,
        String error
) {
    public enum Type {
        SUCCESS,
        RETRYABLE
    }

    public static CallbackOutcome success() {
        return new CallbackOutcome(Type.SUCCESS, null);
    }

    public static CallbackOutcome retryable(String error) {
        return new CallbackOutcome(Type.RETRYABLE, error);
    }
}
