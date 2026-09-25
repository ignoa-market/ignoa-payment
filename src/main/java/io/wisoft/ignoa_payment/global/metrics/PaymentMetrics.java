package io.wisoft.ignoa_payment.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.wisoft.ignoa_payment.callback.entity.CallbackStatus;
import io.wisoft.ignoa_payment.webhook.WebhookResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class PaymentMetrics {

    // 외부 입력인 eventType을 그대로 태그로 쓰면 카디널리티가 폭발하므로 아는 값만 남긴다.
    private static final Set<String> KNOWN_WEBHOOK_EVENTS =
            Set.of("PAYMENT_STATUS_CHANGED", "CANCEL_STATUS_CHANGED", "DEPOSIT_CALLBACK");

    private final MeterRegistry meterRegistry;

    public void recordConfirm(String status) {
        meterRegistry.counter("payment.confirm", "result", status).increment();
    }

    public void recordReconcile(String result) {
        meterRegistry.counter("payment.reconcile", "result", result).increment();
    }

    public void recordCallback(CallbackStatus status) {
        String result = switch (status) {
            case SENT -> "SENT";
            case DEAD -> "DEAD";
            case PENDING -> "RETRY";
        };
        meterRegistry.counter("payment.callback", "result", result).increment();
    }

    public void recordWebhook(String eventType, WebhookResult result) {
        String tag = KNOWN_WEBHOOK_EVENTS.contains(eventType) ? eventType : "OTHER";
        meterRegistry.counter("payment.webhook", "event_type", tag, "result", result.name()).increment();
    }
}
