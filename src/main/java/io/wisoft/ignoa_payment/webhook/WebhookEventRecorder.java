package io.wisoft.ignoa_payment.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 처리 결과와 무관하게 수신 기록이 남도록 별도 트랜잭션으로 저장한다.
@Component
@RequiredArgsConstructor
public class WebhookEventRecorder {

    private static final int MAX_EVENT_TYPE_LENGTH = 50;
    private static final int MAX_ORDER_ID_LENGTH = 64;

    private final WebhookEventRepository webhookEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long record(String eventType, String orderId, String rawBody) {
        return webhookEventRepository.save(WebhookEvent.received(
                truncate(eventType, MAX_EVENT_TYPE_LENGTH),
                truncate(orderId, MAX_ORDER_ID_LENGTH),
                rawBody)).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long eventId, WebhookResult result) {
        webhookEventRepository.findById(eventId).ifPresent(event -> event.complete(result));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
