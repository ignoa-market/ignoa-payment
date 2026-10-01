package io.wisoft.ignoa_payment.global.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 필터 동작만 확인하기 위한 테스트 전용 엔드포인트
@RestController
class FilterProbeController {

    @GetMapping("/internal/probe")
    String internalProbe() {
        return "ok";
    }

    @PostMapping("/payments/probe")
    String paymentsProbe() {
        return "ok";
    }
}
