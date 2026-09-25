package io.wisoft.ignoa_payment.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wisoft.ignoa_payment.global.exception.BusinessException;
import io.wisoft.ignoa_payment.global.exception.ErrorCode;
import io.wisoft.ignoa_payment.global.metrics.PaymentMetrics;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.repository.PaymentRepository;
import io.wisoft.ignoa_payment.payment.service.TossResultApplier;
import io.wisoft.ignoa_payment.toss.TossClient;
import io.wisoft.ignoa_payment.toss.TossLookupResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

// Toss 결제 웹훅에는 서명이 없다. 본문은 "무언가 바뀌었다"는 신호로만 쓰고, 상태는 Toss 조회 결과를 따른다.
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

    private final WebhookEventRecorder webhookEventRecorder;
    private final PaymentRepository paymentRepository;
    private final TossClient tossClient;
    private final TossResultApplier tossResultApplier;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics paymentMetrics;

    public WebhookResult handle(String rawBody) {
        ParsedWebhook parsed = parse(rawBody);
        Long eventId = webhookEventRecorder.record(parsed.eventType(), parsed.orderId(), rawBody);

        WebhookResult result = process(parsed);
        webhookEventRecorder.complete(eventId, result);
        paymentMetrics.recordWebhook(parsed.eventType(), result);

        if (result == WebhookResult.FAILED) {
            // 500을 돌려 Toss가 재전송하게 한다(최대 7회).
            throw new BusinessException(ErrorCode.WEBHOOK_PROCESSING_FAILED);
        }
        log.debug("웹훅 처리 완료: eventType={}, orderId={}, result={}", parsed.eventType(), parsed.orderId(), result);
        return result;
    }

    private WebhookResult process(ParsedWebhook parsed) {
        if (!PAYMENT_STATUS_CHANGED.equals(parsed.eventType()) || parsed.orderId() == null) {
            return WebhookResult.IGNORED;
        }

        Optional<Payment> payment = paymentRepository.findByOrderId(parsed.orderId());
        if (payment.isEmpty()) {
            return WebhookResult.IGNORED;
        }

        TossLookupResult lookup = tossClient.getByOrderId(parsed.orderId());
        return switch (lookup) {
            case TossLookupResult.Found found -> {
                tossResultApplier.apply(payment.get().getId(), found.payment(), LocalDateTime.now());
                yield WebhookResult.APPLIED;
            }
            case TossLookupResult.NotFound notFound -> WebhookResult.IGNORED;
            case TossLookupResult.Failed failed -> {
                log.warn("웹훅 재검증 조회 실패: orderId={}, reason={}, action=Toss 재전송 대기",
                        parsed.orderId(), failed.reason());
                yield WebhookResult.FAILED;
            }
        };
    }

    private ParsedWebhook parse(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root == null || !root.isObject()) {
                return ParsedWebhook.INVALID;
            }
            return new ParsedWebhook(
                    root.path("eventType").asText(null),
                    root.path("data").path("orderId").asText(null));
        } catch (JsonProcessingException e) {
            log.debug("웹훅 본문 파싱 실패: reason={}", e.getOriginalMessage());
            return ParsedWebhook.INVALID;
        }
    }

    private record ParsedWebhook(String eventType, String orderId) {
        static final ParsedWebhook INVALID = new ParsedWebhook("INVALID", null);
    }
}
