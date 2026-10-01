-- Toss 웹훅 수신 로그
CREATE TABLE IF NOT EXISTS webhook_event (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    event_type VARCHAR(50) NULL,
    order_id   VARCHAR(64) NULL,
    raw_body   TEXT        NOT NULL,
    result     ENUM('APPLIED', 'FAILED', 'IGNORED', 'RECEIVED') NOT NULL,
    PRIMARY KEY (id),
    KEY idx_webhook_event_order_id (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
