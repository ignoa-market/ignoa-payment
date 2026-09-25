package io.wisoft.ignoa_payment.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 외부에 공개되는 유일한 경로. CloudFront를 거쳐 들어온다.
@RestController
@RequiredArgsConstructor
public class TossWebhookController {

    private final WebhookService webhookService;

    @PostMapping("/payments/webhook")
    public ResponseEntity<Void> receive(@RequestBody(required = false) String rawBody) {
        webhookService.handle(rawBody == null ? "" : rawBody);
        return ResponseEntity.ok().build();
    }
}
