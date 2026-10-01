package io.wisoft.ignoa_payment.callback.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import io.wisoft.ignoa_payment.payment.dto.PaymentResultResponse;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class CallbackAppender {

    private final PaymentCallbackRepository callbackRepository;
    private final ObjectMapper objectMapper;

    // 결제 상태 변경과 같은 트랜잭션에서만 호출한다. 상태가 커밋되면 콜백도 반드시 남는다.
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(Payment payment, LocalDateTime now) {
        callbackRepository.save(PaymentCallback.pending(
                payment.getId(), payment.getTradeId(), toPayload(payment), now));
    }

    private String toPayload(Payment payment) {
        try {
            return objectMapper.writeValueAsString(PaymentResultResponse.from(payment));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("콜백 본문 직렬화 실패: paymentId=" + payment.getId(), e);
        }
    }
}
