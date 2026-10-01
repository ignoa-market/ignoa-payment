package io.wisoft.ignoa_payment.toss;

public sealed interface TossLookupResult {

    record Found(TossPayment payment) implements TossLookupResult {
    }

    record NotFound() implements TossLookupResult {
    }

    record Failed(String reason) implements TossLookupResult {
    }
}
