package io.wisoft.ignoa_payment.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

// Toss Payment 객체 중 결제 서버가 쓰는 필드만 받는다(나머지는 무시).
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(
        String paymentKey,
        String orderId,
        String status,
        Long totalAmount,
        OffsetDateTime approvedAt
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public LocalDateTime approvedAtInSeoul() {
        return approvedAt == null ? null : approvedAt.atZoneSameInstant(SEOUL).toLocalDateTime();
    }
}
