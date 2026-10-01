package io.wisoft.ignoa_payment.callback.scheduler;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class CallbackDispatchScheduler {

    private final CallbackDispatchJob callbackDispatchJob;

    @Scheduled(fixedDelay = 10_000L)
    @SchedulerLock(name = "callbackDispatchScheduler")
    public void dispatch() {
        callbackDispatchJob.execute(LocalDateTime.now());
    }
}
