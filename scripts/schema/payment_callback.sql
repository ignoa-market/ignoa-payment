-- 결제 결과 콜백 Outbox (ignoa-api로 보낼 결과)
CREATE TABLE IF NOT EXISTS payment_callback (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    payment_id        BIGINT       NOT NULL,
    trade_id          BIGINT       NOT NULL,
    payload           TEXT         NOT NULL,
    status            ENUM('DEAD', 'PENDING', 'SENT') NOT NULL,
    attempts          INT          NOT NULL,
    next_attempt_at   DATETIME(6)  NOT NULL,
    first_enqueued_at DATETIME(6)  NOT NULL,
    last_error        VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY idx_payment_callback_status_next_attempt_at (status, next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
