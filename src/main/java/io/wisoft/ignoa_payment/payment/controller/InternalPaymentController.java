package io.wisoft.ignoa_payment.payment.controller;

import io.wisoft.ignoa_payment.global.common.ApiResponse;
import io.wisoft.ignoa_payment.payment.dto.PaymentPrepareRequest;
import io.wisoft.ignoa_payment.payment.dto.PaymentPrepareResponse;
import io.wisoft.ignoa_payment.payment.dto.PaymentResultResponse;
import io.wisoft.ignoa_payment.payment.entity.Payment;
import io.wisoft.ignoa_payment.payment.service.PaymentReader;
import io.wisoft.ignoa_payment.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;
    private final PaymentReader paymentReader;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentPrepareResponse>> prepare(
            @Valid @RequestBody PaymentPrepareRequest request) {
        Payment payment = paymentService.prepare(request.tradeId(), request.amount(), request.orderName());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(PaymentPrepareResponse.from(payment), "결제를 준비했습니다."));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<PaymentResultResponse>> getStatus(@PathVariable String orderId) {
        Payment payment = paymentReader.getByOrderId(orderId);
        return ResponseEntity.ok(ApiResponse.of(PaymentResultResponse.from(payment), "결제 상태를 조회했습니다."));
    }
}
