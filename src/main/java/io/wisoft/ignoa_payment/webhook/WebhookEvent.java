package io.wisoft.ignoa_payment.webhook;

import io.wisoft.ignoa_payment.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Toss 웹훅 수신 로그. 추적용이며 멱등은 결제 상태 전이가 보장한다.
@Entity
@Table(name = "webhook_event", indexes = {
        @Index(name = "idx_webhook_event_order_id", columnList = "order_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "raw_body", nullable = false, columnDefinition = "TEXT")
    private String rawBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookResult result;

    public static WebhookEvent received(String eventType, String orderId, String rawBody) {
        WebhookEvent event = new WebhookEvent();
        event.eventType = eventType;
        event.orderId = orderId;
        event.rawBody = rawBody;
        event.result = WebhookResult.RECEIVED;
        return event;
    }

    public void complete(WebhookResult result) {
        this.result = result;
    }
}
