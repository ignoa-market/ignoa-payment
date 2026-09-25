package io.wisoft.ignoa_payment.toss;

public interface TossClient {

    TossConfirmResult confirm(String paymentKey, String orderId, long amount);

    TossLookupResult getByOrderId(String orderId);

    boolean cancel(String paymentKey, String cancelReason);
}
