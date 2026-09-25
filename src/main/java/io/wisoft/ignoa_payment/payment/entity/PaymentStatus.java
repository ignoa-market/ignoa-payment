package io.wisoft.ignoa_payment.payment.entity;

public enum PaymentStatus {
    READY,       // 준비됨, 승인 요청 전
    CONFIRMING,  // Toss에 승인 요청을 보냈고 결과 미확정
    DONE,        // 승인 완료(돈이 빠져나감)
    FAILED,      // 승인되지 않음
    CANCELED     // 승인 후 Toss에서 취소됨(웹훅으로만 확인)
}
