-- 결제 테이블
-- 운영(prod, ddl-auto: validate)은 이 파일로 만든다. 엔티티(Payment)를 바꾸면 이 파일도 함께 고친다.
-- uk_payment_paid_trade_id: DONE일 때만 채워지는 paid_trade_id로 "trade당 성공 결제 1건"을 DB가 보장한다(이중 결제 최후 방어선).
CREATE TABLE IF NOT EXISTS payment (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    order_id             VARCHAR(64)  NOT NULL,
    trade_id             BIGINT       NOT NULL,
    payment_key          VARCHAR(200) NULL,
    amount               BIGINT       NOT NULL,
    order_name           VARCHAR(100) NOT NULL,
    status               ENUM('CANCELED', 'CONFIRMING', 'DONE', 'FAILED', 'READY') NOT NULL,
    paid_trade_id        BIGINT       NULL,
    failure_code         ENUM('ALREADY_PAID', 'AMOUNT_MISMATCH', 'EXPIRED', 'TOSS_REJECTED') NULL,
    failure_message      VARCHAR(500) NULL,
    toss_status          VARCHAR(30)  NULL,
    approved_at          DATETIME(6)  NULL,
    confirm_requested_at DATETIME(6)  NULL,
    version              BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order_id (order_id),
    UNIQUE KEY uk_payment_payment_key (payment_key),
    UNIQUE KEY uk_payment_paid_trade_id (paid_trade_id),
    KEY idx_payment_trade_id (trade_id),
    KEY idx_payment_status_confirm_requested_at (status, confirm_requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
