# ignoa-payment

Ignoa 경매 마켓의 결제 서버. ignoa-api의 주문을 Toss Payments로 결제한다.

- 연동 가이드(ignoa-api용): `docs/superpowers/specs/2026-09-25-payment-integration-contract.md`
- 내부 설계: `docs/superpowers/specs/2026-09-25-ignoa-payment-design.md`

## 로컬 실행

```bash
cp .env.example .env          # 값 확인 (Toss 키는 공식 샘플 테스트 키)
docker compose up -d mysql    # MySQL 43306, shedlock 테이블 자동 생성
./gradlew bootRun             # 포트 48080, 프로파일 local
```

## 테스트

```bash
./gradlew test   # Docker 데몬 필요(Testcontainers)
```

## API

| 메서드 | 경로 | 인증 | 설명 |
| --- | --- | --- | --- |
| POST | `/internal/payments` | `X-Internal-Api-Key` | 결제 준비 |
| POST | `/internal/payments/confirm` | `X-Internal-Api-Key` | 결제 승인 |
| GET | `/internal/payments/{orderId}` | `X-Internal-Api-Key` | 결제 상태 조회 |
| POST | `/payments/webhook` | (prod: `X-Origin-Verify`) | Toss 웹훅 |
