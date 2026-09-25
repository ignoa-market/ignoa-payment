package io.wisoft.ignoa_payment.callback.scheduler;

import io.wisoft.ignoa_payment.callback.entity.CallbackStatus;
import io.wisoft.ignoa_payment.callback.entity.PaymentCallback;
import io.wisoft.ignoa_payment.callback.repository.PaymentCallbackRepository;
import io.wisoft.ignoa_payment.callback.service.CallbackOutcome;
import io.wisoft.ignoa_payment.callback.service.CallbackService;
import io.wisoft.ignoa_payment.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CallbackDispatchJobTest extends IntegrationTestSupport {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 12, 0, 0);

    @Autowired
    private CallbackDispatchJob job;

    @Autowired
    private PaymentCallbackRepository callbackRepository;

    private PaymentCallback enqueue() {
        return callbackRepository.save(PaymentCallback.pending(1L, 10L, "{\"status\":\"DONE\"}", T0));
    }

    private PaymentCallback reload(PaymentCallback callback) {
        return callbackRepository.findById(callback.getId()).orElseThrow();
    }

    @Test
    void 성공하면_SENT가_된다() {
        PaymentCallback callback = enqueue();
        given(apiCallbackClient.send(10L, "{\"status\":\"DONE\"}")).willReturn(CallbackOutcome.success());

        job.execute(T0);

        assertThat(reload(callback).getStatus()).isEqualTo(CallbackStatus.SENT);
    }

    @Test
    void 재시도_가능한_실패는_백오프_후_다시_보낸다() {
        PaymentCallback callback = enqueue();
        given(apiCallbackClient.send(anyLong(), anyString())).willReturn(CallbackOutcome.retryable("HTTP 503"));

        job.execute(T0);

        PaymentCallback found = reload(callback);
        assertThat(found.getStatus()).isEqualTo(CallbackStatus.PENDING);
        assertThat(found.getAttempts()).isEqualTo(1);
        assertThat(found.getNextAttemptAt()).isEqualTo(T0.plusMinutes(1));
        assertThat(found.getLastError()).isEqualTo("HTTP 503");
    }

    @Test
    void 다음_시도_시각_전에는_보내지_않는다() {
        enqueue();
        given(apiCallbackClient.send(anyLong(), anyString())).willReturn(CallbackOutcome.retryable("HTTP 503"));
        job.execute(T0);

        job.execute(T0.plusSeconds(30));

        verify(apiCallbackClient, times(1)).send(anyLong(), anyString());
    }

    @Test
    void 영구_실패는_바로_DEAD다() {
        PaymentCallback callback = enqueue();
        given(apiCallbackClient.send(anyLong(), anyString())).willReturn(CallbackOutcome.permanent("HTTP 404"));

        job.execute(T0);

        assertThat(reload(callback).getStatus()).isEqualTo(CallbackStatus.DEAD);
    }

    @Test
    void 최초_적재_후_24시간이_지나면_DEAD다() {
        PaymentCallback callback = enqueue();
        given(apiCallbackClient.send(anyLong(), anyString())).willReturn(CallbackOutcome.retryable("HTTP 503"));

        job.execute(T0.plusHours(24));

        assertThat(reload(callback).getStatus()).isEqualTo(CallbackStatus.DEAD);
    }

    @Test
    void SENT와_DEAD는_다시_보내지_않는다() {
        PaymentCallback callback = enqueue();
        given(apiCallbackClient.send(anyLong(), anyString())).willReturn(CallbackOutcome.success());
        job.execute(T0);

        job.execute(T0.plusMinutes(10));

        verify(apiCallbackClient, times(1)).send(anyLong(), anyString());
        assertThat(reload(callback).getStatus()).isEqualTo(CallbackStatus.SENT);
    }

    @Test
    void 대기_중인_콜백이_없으면_아무것도_보내지_않는다() {
        job.execute(T0);

        verify(apiCallbackClient, never()).send(anyLong(), anyString());
    }

    @Test
    void 재시도_간격은_1_2_4_8_16분_이후_30분이다() {
        assertThat(CallbackService.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(CallbackService.delayAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(CallbackService.delayAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(CallbackService.delayAfter(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(CallbackService.delayAfter(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(CallbackService.delayAfter(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(CallbackService.delayAfter(20)).isEqualTo(Duration.ofMinutes(30));
    }
}
