package io.wisoft.ignoa_payment.payment.scheduler;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ConfirmReconcileScheduler {

    private final ConfirmReconcileJob confirmReconcileJob;

    @Scheduled(fixedDelay = 60_000L)
    @SchedulerLock(name = "confirmReconcileScheduler")
    public void reconcile() {
        confirmReconcileJob.execute(LocalDateTime.now());
    }
}
