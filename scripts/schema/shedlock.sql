-- ShedLock 잠금 저장소 테이블
-- JPA 엔티티가 아니므로 ddl-auto로 생성되지 않는다.
-- docker-compose의 MySQL은 최초 기동 시 이 디렉터리를 자동 실행한다.
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until DATETIME(3)  NOT NULL,
    locked_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
