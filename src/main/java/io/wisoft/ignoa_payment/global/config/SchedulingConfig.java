package io.wisoft.ignoa_payment.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 테스트에서는 ignoa.scheduling.enabled=false로 꺼서 스케줄러가 테스트 데이터를 건드리지 않게 한다.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ignoa.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
