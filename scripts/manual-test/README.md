# 결제 수동 테스트

ignoa-api·ignoa-web 없이 **결제 서버 ↔ Toss** 흐름(준비 → 결제창 → 승인)을 확인하는 로컬 전용 페이지.
Toss **테스트 키**만 사용하므로 실제 돈은 나가지 않는다.

## 준비

- `.env`에 본인 시크릿 키(`TOSS_SECRET_KEY=test_gsk_…`)와 `INTERNAL_API_KEY`가 채워져 있어야 한다.
- Toss 개발자센터의 **결제위젯 연동 키** 중 클라이언트 키(`test_gck_…`)를 준비한다. 시크릿 키와 같은 세트여야 한다.

## 순서

```bash
# 1. 결제 서버 실행 (ignoa-payment 폴더)
docker compose up -d mysql
./gradlew bootRun

# 2. 결제 준비 → order_id 받기
KEY=$(grep '^INTERNAL_API_KEY=' .env | cut -d= -f2)
curl -s -X POST localhost:48080/internal/payments \
  -H "X-Internal-Api-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"trade_id":1,"amount":1000,"order_name":"테스트"}'

# 3. 테스트 페이지 띄우기 (다른 터미널)
python3 -m http.server 5500 -d scripts/manual-test
```

4. 브라우저에서 `http://localhost:5500` 접속 → 클라이언트 키, `order_id`, `amount`(준비와 같은 값) 입력 → 결제창 불러오기 → 결제하기
5. 결제 인증이 끝나면 페이지에 **승인 curl 명령**이 표시된다. 10분 안에 `ignoa-payment` 폴더에서 실행한다.
6. 응답 `status`가 `DONE`이면 성공. `GET /internal/payments/{orderId}`로 다시 확인할 수 있다.

## 확인해 볼 만한 경우

- 준비 금액과 다른 `amount`로 승인 → `FAILED` / `AMOUNT_MISMATCH`, Toss 호출 없음
- 다른 `trade_id`로 승인 → `409 PAYMENT_TRADE_MISMATCH`
- 같은 승인 명령을 한 번 더 실행 → 같은 `DONE` 응답(멱등)
- 결제창에서 취소 → 실패 화면(`PAY_PROCESS_CANCELED` 등)

## 참고

- 결제 결과 콜백은 `API_BASE_URL`(ignoa-api)로 보낸다. api가 떠 있지 않으면 콜백은 재시도 대기 상태로 남는다(24시간 후 DEAD). 이 테스트에서는 무시해도 된다.
- 클라이언트 키는 브라우저 localStorage에만 저장되며 저장소에 커밋되지 않는다.
