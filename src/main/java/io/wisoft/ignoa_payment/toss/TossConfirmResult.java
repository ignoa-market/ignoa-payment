package io.wisoft.ignoa_payment.toss;

public sealed interface TossConfirmResult {

    // 200 응답. status가 DONE이 아닐 수도 있다(예: WAITING_FOR_DEPOSIT).
    record Responded(TossPayment payment) implements TossConfirmResult {
    }

    // 4xx 거절. 돈은 빠져나가지 않았다.
    record Rejected(String code, String message) implements TossConfirmResult {
    }

    // 400 ALREADY_PROCESSED_PAYMENT. 실패가 아니라 조회로 실제 상태를 확인해야 한다.
    record AlreadyProcessed() implements TossConfirmResult {
    }

    // 404 NOT_FOUND_PAYMENT_SESSION. 결제 인증 후 승인 가능 시간이 지났다.
    record SessionExpired() implements TossConfirmResult {
    }

    // 타임아웃·5xx·네트워크 오류. 돈이 빠져나갔을 수도 있다.
    record Unknown(String reason) implements TossConfirmResult {
    }
}
