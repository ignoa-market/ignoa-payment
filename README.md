# ignoa-payment 연동 가이드

`ignoa-payment`는 Toss Payments 승인과 결제 상태를 전담하는 독립 서버입니다.
`ignoa-api`는 사용자와 거래를 검증하고, 결제 서버의 결과를 거래 상태에 반영합니다.

이 문서는 `ignoa-api`에서 결제 기능을 개발하고 운영할 때 필요한 연동 규칙을 설명합니다. 결제 서버 내부 구현보다 **두 서버 사이의 계약, 상태 전이, 실패 복구 방법**을 기준으로 작성했습니다.

## 1. 연동 원칙

### 책임 경계

| 책임 | ignoa-api | ignoa-payment |
| --- | --- | --- |
| 구매자 인증 및 거래 접근 권한 검사 | O | X |
| 거래 금액과 상품명 결정 | O | X |
| 거래·상품 상태 변경 | O | X |
| `order_id` 발급 | X | O |
| Toss 결제 승인 및 조회 | X | O |
| 결제 상태 저장 | X | O |
| 미확정 결제 재조회 | X | O |
| 결제 결과 콜백 발송 | X | O |

두 서버는 데이터베이스를 공유하지 않습니다. `trade_id`와 `order_id`로만 결제 시도와 거래를 연결합니다.

### 반드시 지켜야 할 규칙

1. 결제 금액과 상품명은 클라이언트 입력을 신뢰하지 않고 `ignoa-api`의 거래에서 가져옵니다.
2. 승인 요청 전에 거래를 `CONFIRMING`으로 변경하고 현재 `order_id`를 저장합니다.
3. 승인 결과는 응답과 콜백 양쪽으로 올 수 있으므로 멱등하게 반영합니다.
4. 현재 거래의 `confirming_order_id`와 다른 결과는 이전 결제 시도의 결과이므로 무시합니다.
5. 승인 요청의 5xx, 네트워크 오류, 타임아웃은 실패가 아니라 **결과 미확정**으로 취급합니다.
6. 거래 하나에 결제 시도는 여러 번 만들 수 있지만 성공 결제는 하나만 허용합니다. 결제 서버도 DB 제약으로 이를 보장합니다.

## 2. 전체 결제 흐름

```text
1. 결제 준비
   FE → ignoa-api → ignoa-payment
   POST /internal/payments
   결제 서버가 order_id를 발급하고 READY 상태로 저장

2. Toss 인증
   FE ↔ Toss Payments
   결제창 인증 성공 후 FE가 payment_key, order_id, amount를 받음

3. 결제 승인
   FE → ignoa-api
   ignoa-api가 거래를 CONFIRMING으로 변경하고 order_id 저장
   ignoa-api → ignoa-payment
   POST /internal/payments/confirm

4. 결과 반영
   즉시 확정: 승인 응답의 DONE 또는 FAILED를 반영
   미확정: UNKNOWN 상태로 콜백 또는 상태 조회를 기다림

5. 비동기 확정
   ignoa-payment → ignoa-api
   POST /internal/trades/{tradeId}/payment-result
```

승인 응답과 콜백의 도착 순서는 보장되지 않습니다. 동일한 결과가 여러 번 도착하거나 이전 결제 시도의 콜백이 늦게 도착할 수 있습니다.

## 3. ignoa-api 구현 가이드

### 3.1 결제 준비

FE 공개 API 예시:

```http
POST /api/trades/{tradeId}/payments
Authorization: Bearer {accessToken}
```

처리 순서:

1. 로그인 사용자가 해당 거래의 구매자인지 확인합니다.
2. 거래가 `PAYMENT_PENDING`인지 확인합니다.
3. 결제 기한이 지나지 않았는지 확인합니다.
4. 즉시구매 거래라면 상품이 아직 구매 가능한지 확인합니다.
5. 거래에 저장된 금액과 상품명으로 결제 서버의 준비 API를 호출합니다.
6. 받은 `order_id`, `amount`, `order_name`을 FE에 전달합니다.

결제 준비 호출 자체는 거래 상태를 변경하지 않습니다. 같은 거래로 다시 준비하면 새로운 `order_id`가 발급됩니다.

### 3.2 결제 승인

FE 공개 API 예시:

```http
POST /api/trades/{tradeId}/payments/confirm
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "order_id": "IGN-...",
  "payment_key": "tgen_...",
  "amount": 1000000
}
```

처리 순서:

1. 구매자와 거래 상태를 다시 검증합니다.
2. 거래를 `PAYMENT_PENDING → CONFIRMING`으로 조건부 변경합니다.
3. 거래에 `confirming_order_id`와 승인 시작 시각을 저장합니다.
4. 즉시구매 거래라면 상품을 예약합니다.
5. 결제 서버의 승인 API를 호출합니다.
6. 응답 상태에 따라 거래 결과를 반영합니다.

동시에 두 승인 요청이 들어오더라도 한 요청만 `CONFIRMING` 전이에 성공해야 합니다.

### 3.3 승인 결과 처리

| 결과 | ignoa-api 처리 |
| --- | --- |
| `DONE` | 현재 `order_id`가 맞으면 거래를 `PAID`로 변경하고 상품 후처리 수행 |
| `FAILED` | 현재 `order_id`가 맞으면 거래 유형에 맞는 실패 상태로 변경 |
| `UNKNOWN` | `CONFIRMING`을 유지하고 콜백 또는 상태 조회를 기다림 |
| 결제 서버 4xx | Toss 호출 전 거절이므로 승인 시작 상태를 되돌리고 FE에 오류 반환 |
| 결제 서버 5xx·타임아웃·연결 오류 | Toss에서 승인됐을 수 있으므로 `UNKNOWN`으로 취급 |

현재 거래별 실패 처리는 다음과 같습니다.

| 거래 유형 | 결제 실패 후 상태 | 상품 처리 |
| --- | --- | --- |
| 경매 낙찰 | `PAYMENT_PENDING` | 다시 결제할 수 있도록 유지 |
| 즉시구매 | `CANCELED` | 상품 예약 해제 |

`DONE` 또는 `FAILED`를 반영할 때는 반드시 아래 조건을 함께 검사해야 합니다.

```text
trade.status == CONFIRMING
trade.confirming_order_id == result.order_id
```

조건이 맞지 않으면 이미 반영된 결과이거나 이전 결제 시도의 결과이므로 성공 응답을 반환하되 상태를 변경하지 않습니다.

### 3.4 콜백 수신

결제 서버는 결제가 `DONE` 또는 `FAILED`로 확정되면 다음 API를 호출합니다.

```http
POST /internal/trades/{tradeId}/payment-result
X-Internal-Api-Key: {INTERNAL_API_KEY}
Content-Type: application/json
```

```json
{
  "trade_id": 1,
  "order_id": "IGN-...",
  "status": "DONE",
  "amount": 1000000,
  "approved_at": "2026-10-03T19:16:27",
  "failure_code": null,
  "failure_message": null
}
```

콜백 처리 규칙:

- `tradeId`의 거래가 없거나 처리할 수 없으면 비정상 응답을 반환합니다.
- 유효한 콜백은 결과가 이미 반영됐더라도 `2xx`를 반환합니다.
- `order_id`가 현재 결제 시도와 다르면 상태를 변경하지 않고 `2xx`를 반환합니다.
- `DONE`, `FAILED` 외 상태는 거래를 변경하지 않습니다.
- 콜백에서 전달된 금액만으로 거래 금액을 변경하지 않습니다.

결제 서버는 `2xx`를 받지 못하면 1, 2, 4, 8, 16분, 이후 30분 간격으로 재시도하며 최초 적재 후 24시간이 지나면 중단합니다.

### 3.5 정체 거래 복구

승인 응답과 콜백을 모두 받지 못하면 거래가 `CONFIRMING`에 남을 수 있습니다. `ignoa-api`는 주기적으로 이런 거래를 찾아 결제 상태 조회 API를 호출해야 합니다.

현재 구현 기준:

- 10분 이상 `CONFIRMING`인 거래를 대상으로 합니다.
- 스케줄러는 5분마다 실행됩니다.
- 조회 결과가 `READY`면 결제 서버에 승인 요청이 도착하지 않은 것이므로 승인 시작 상태를 되돌립니다.
- `DONE` 또는 `FAILED`면 일반 결과 처리와 동일하게 반영합니다.
- `CONFIRMING`이면 다음 주기에 다시 확인합니다.
- 조회 실패는 상태를 변경하지 않고 다음 주기에 재시도합니다.

## 4. 상태 모델

### ignoa-api 거래 상태

```text
PAYMENT_PENDING ── 승인 시작 ──> CONFIRMING ── 결제 성공 ──> PAID
       ▲                           │
       │                           ├─ 경매 결제 실패 ───────┘
       │                           │
       │                           └─ 즉시구매 결제 실패 ──> CANCELED
       │
       └─ 승인 요청이 결제 서버에 도착하지 않은 경우 복구
```

### ignoa-payment 결제 상태

| 상태 | 의미 |
| --- | --- |
| `READY` | 결제 준비만 완료되어 Toss 승인 요청이 시작되지 않음 |
| `CONFIRMING` | Toss 승인 결과를 확인 중 |
| `DONE` | 결제 승인 완료 |
| `FAILED` | 결제 실패 확정 |
| `CANCELED` | 승인 후 Toss에서 취소됨. 현재 거래 콜백 대상은 아님 |

승인 API는 `READY`나 `CONFIRMING`처럼 결론이 나지 않은 상태를 `UNKNOWN`으로 반환합니다. 상태 조회 API는 실제 저장 상태를 그대로 반환합니다.

## 5. 장애 상황별 처리

| 상황 | 해석 | 조치 |
| --- | --- | --- |
| 준비 API 실패 | 결제 시도 생성 여부를 신뢰할 수 없음 | 거래는 `PAYMENT_PENDING` 유지, 사용자가 다시 준비 가능 |
| 승인 API 400·404·409 | Toss 호출 전 요청 거절 | `CONFIRMING`을 되돌리고 오류 반환 |
| 승인 API 5xx 또는 타임아웃 | Toss 승인 여부 미확정 | `CONFIRMING` 유지, 콜백·상태 조회 대기 |
| 승인 응답 `UNKNOWN` | 결제 서버도 결과 확인 중 | `CONFIRMING` 유지 |
| 콜백 중복 | 정상적인 재전송 가능 | 멱등 처리 후 `2xx` 반환 |
| 이전 `order_id` 콜백 | 과거 결제 시도의 늦은 결과 | 현재 거래 상태를 변경하지 않고 `2xx` 반환 |
| 결제 서버 조회 실패 | 일시 장애 가능 | 상태를 변경하지 않고 다음 주기 재시도 |
| 콜백 재시도 24시간 초과 | 자동 전달 중단 | 결제 서버 상태와 거래 상태를 수동 대조 |

`UNKNOWN`이나 통신 오류를 결제 실패로 간주해 상품 예약을 즉시 해제하면, 실제 결제는 성공했는데 상품이 다시 판매되는 문제가 생길 수 있습니다.

## 6. 설정과 배포

### ignoa-api

| 환경 변수 | 설명 |
| --- | --- |
| `PAYMENT_BASE_URL` | `ignoa-payment` 내부 접근 주소 |
| `INTERNAL_API_KEY` | 결제 서버와 공유하는 내부 API 키 |

결제 서버 호출의 현재 타임아웃은 연결 3초, 응답 25초입니다.

### ignoa-payment

| 환경 변수 | 설명 |
| --- | --- |
| `TOSS_SECRET_KEY` | Toss Payments 시크릿 키 |
| `TOSS_BASE_URL` | Toss Payments API 주소. 기본값 `https://api.tosspayments.com` |
| `INTERNAL_API_KEY` | `ignoa-api`와 공유하는 내부 API 키 |
| `API_BASE_URL` | 콜백을 받을 `ignoa-api` 주소 |
| `CLOUDFRONT_ORIGIN_VERIFY_SECRET` | Toss 웹훅 요청의 `X-Origin-Verify` 검증값 |
| `MYSQL_HOST` | 운영 MySQL 호스트 |
| `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | MySQL 접속 정보 |
| `SENTRY_DSN` | 운영 오류 수집 DSN |

권장 배포 순서:

1. `ignoa-api`에 콜백 API와 내부 API 키 검증을 먼저 배포합니다.
2. 양쪽 서버에 같은 `INTERNAL_API_KEY`를 설정합니다.
3. 결제 서버의 `API_BASE_URL`과 API 서버의 `PAYMENT_BASE_URL`을 설정합니다.
4. 상호 health check와 내부 네트워크 접근을 확인합니다.
5. 결제 서버를 배포하고 테스트 결제로 전체 흐름을 검증합니다.
6. FE에서 결제 준비·인증·승인 흐름을 활성화합니다.

키를 교체할 때 한쪽만 먼저 변경하면 내부 호출과 콜백이 `401`이 됩니다. 무중단 키 교체가 필요하면 배포 전에 복수 키 허용 전략을 별도로 마련해야 합니다.

## 7. 연동 완료 체크리스트

### 개발

- [ ] 결제 준비 시 구매자, 거래 상태, 결제 기한을 검증한다.
- [ ] 금액과 상품명은 `ignoa-api` 데이터로 만든다.
- [ ] 승인 전에 거래를 조건부로 `CONFIRMING`으로 변경한다.
- [ ] 현재 `order_id`를 거래에 저장한다.
- [ ] 승인 결과 `DONE`, `FAILED`, `UNKNOWN`을 구분한다.
- [ ] 4xx와 5xx·타임아웃을 다르게 처리한다.
- [ ] 콜백 API에 내부 API 키 검증을 적용한다.
- [ ] 승인 응답과 콜백을 같은 멱등 처리 로직으로 반영한다.
- [ ] 정체된 `CONFIRMING` 거래를 상태 조회로 복구한다.

### 테스트

- [ ] 정상 결제가 `PAID`까지 전이된다.
- [ ] Toss 거절 시 거래 유형에 맞는 실패 상태가 된다.
- [ ] 잘못된 금액으로 승인하면 `AMOUNT_MISMATCH`가 반영된다.
- [ ] 승인 응답이 유실되어도 콜백으로 결제가 확정된다.
- [ ] 승인 응답과 콜백이 중복되어도 한 번만 반영된다.
- [ ] 이전 `order_id`의 늦은 콜백이 현재 거래를 변경하지 않는다.
- [ ] 동일 거래의 동시 승인 요청 중 하나만 처리된다.
- [ ] 승인 타임아웃 후 상품이 잘못 재판매되지 않는다.
- [ ] 결제 서버 장애 후 상태 조회로 정체 거래가 복구된다.
- [ ] 잘못된 내부 API 키 요청이 `401`로 거절된다.

### 운영

- [ ] 양쪽 서버의 `INTERNAL_API_KEY`가 일치한다.
- [ ] `ignoa-payment`에서 `ignoa-api` 콜백 주소에 접근할 수 있다.
- [ ] `ignoa-api`에서 결제 서버 내부 주소에 접근할 수 있다.
- [ ] 결제 서버의 콜백 `DEAD` 로그를 모니터링한다.
- [ ] 장시간 `CONFIRMING`인 거래를 탐지할 수 있다.
- [ ] 결제 성공 건수와 거래 `PAID` 건수를 정기적으로 대조한다.

## 8. 내부 API 계약

### 공통

- `/internal/**` 요청에는 `X-Internal-Api-Key` 헤더가 필요합니다.
- 키가 없거나 일치하지 않으면 `401`과 `INVALID_INTERNAL_API_KEY`를 반환합니다.
- JSON 필드는 snake_case입니다.
- 성공 응답: `{ "data": { ... }, "message": "..." }`
- 실패 응답: `{ "code": "PAYMENT_NOT_FOUND", "message": "...", "details": [] }`

### 결제 준비

```http
POST /internal/payments
X-Internal-Api-Key: {INTERNAL_API_KEY}
Content-Type: application/json
```

```json
{
  "trade_id": 1,
  "amount": 1000000,
  "order_name": "상품명"
}
```

성공 시 `201 Created`:

```json
{
  "data": {
    "order_id": "IGN-ea0028bf...",
    "trade_id": 1,
    "amount": 1000000,
    "order_name": "상품명"
  },
  "message": "결제를 준비했습니다."
}
```

- `trade_id`와 `amount`는 1 이상이어야 합니다.
- `order_name`은 공백일 수 없고 최대 100자입니다.
- 호출할 때마다 새로운 `order_id`를 발급합니다.
- 30분 안에 승인하지 않은 `READY` 결제는 `FAILED(EXPIRED)`로 처리하고 콜백을 보냅니다.

### 결제 승인

```http
POST /internal/payments/confirm
X-Internal-Api-Key: {INTERNAL_API_KEY}
Content-Type: application/json
```

```json
{
  "trade_id": 1,
  "order_id": "IGN-...",
  "payment_key": "tgen_...",
  "amount": 1000000
}
```

성공적으로 요청을 처리하면 `200 OK`:

```json
{
  "data": {
    "trade_id": 1,
    "order_id": "IGN-...",
    "status": "DONE",
    "amount": 1000000,
    "approved_at": "2026-10-03T19:16:27",
    "failure_code": null,
    "failure_message": null
  },
  "message": "결제 승인 요청을 처리했습니다."
}
```

| status | 설명 |
| --- | --- |
| `DONE` | 결제 완료 |
| `FAILED` | 결제 실패. `failure_code` 확인 필요 |
| `UNKNOWN` | 결과 미확정. 콜백 또는 상태 조회 필요 |

같은 요청을 다시 보내면 Toss를 다시 호출하지 않고 저장된 결과를 반환합니다.

| HTTP | code | 설명 |
| --- | --- | --- |
| 400 | `INVALID_INPUT_VALUE` | 필수값 누락 또는 형식 오류 |
| 404 | `PAYMENT_NOT_FOUND` | 존재하지 않는 `order_id` |
| 409 | `PAYMENT_TRADE_MISMATCH` | `order_id`와 `trade_id`가 일치하지 않음 |
| 409 | `PAYMENT_KEY_MISMATCH` | 기존 요청과 다른 키이거나 다른 주문에 사용된 키 |

### 결제 상태 조회

```http
GET /internal/payments/{orderId}
X-Internal-Api-Key: {INTERNAL_API_KEY}
```

성공 시 `200 OK`이며 응답 `data` 형식은 승인 결과와 같습니다. 승인 API와 달리 `UNKNOWN`으로 변환하지 않고 `READY`, `CONFIRMING`, `DONE`, `FAILED`, `CANCELED` 중 실제 저장 상태를 반환합니다.

없는 `order_id`는 `404`와 `PAYMENT_NOT_FOUND`를 반환합니다.

### failure_code

| 값 | 설명 |
| --- | --- |
| `AMOUNT_MISMATCH` | 승인 요청 금액이 준비 금액과 다름 |
| `ALREADY_PAID` | 같은 거래에 이미 성공한 결제가 있음 |
| `TOSS_REJECTED` | Toss 승인 거절 또는 취소 |
| `EXPIRED` | 결제 유효 시간 30분 초과 |

## 9. 결제 서버의 자동 복구 작업

| 작업 | 주기 | 내용 |
| --- | --- | --- |
| 승인 재확인 | 60초 | 1분 넘게 `CONFIRMING`인 결제를 Toss에 조회해 확정 |
| 준비·승인 만료 | 60초 | 30분 넘게 결론 나지 않은 결제를 `FAILED(EXPIRED)`로 확정 |
| 콜백 발송 | 10초 | 전송 시각이 된 콜백을 최대 100건씩 발송 |
| Toss 웹훅 | 수신 시 | `POST /payments/webhook`; 웹훅 본문을 신뢰하지 않고 Toss 조회 결과로 반영 |

## 10. 로컬 실행과 수동 테스트

```bash
cp .env.example .env
docker compose up -d mysql   # MySQL 43306
./gradlew bootRun            # 애플리케이션 48080, local 프로파일
./gradlew test               # 통합 테스트에 Docker 필요
```

결제창을 이용한 수동 테스트는 [`scripts/manual-test/README.md`](scripts/manual-test/README.md)를 참고하세요.
