-- 녹화 orchestration용 transactional outbox.
-- 비즈니스 쓰기(세션 생성, 트랙 발행 처리)와 같은 트랜잭션으로 행을 삽입하고(INSERT IGNORE로 dedup 충돌이
-- 호출자 트랜잭션을 오염시키지 않게 한다), 릴레이가 PENDING 행을 claim(IN_PROGRESS 전환)한 뒤 외부 작업을 수행한다.
-- dedup_key UNIQUE가 같은 작업의 중복 등록을, claim이 다중 인스턴스·재시작 시 중복 수행을 막는다.
-- sessions FK는 두지 않는다: outbox는 세션 행과 잠금 결합 없이 독립적으로 소비·정리되는 큐 성격의 테이블이다.
CREATE TABLE `recording_outbox` (
    `id`              BIGINT        NOT NULL COMMENT 'TSID 기본키',
    `dedup_key`       VARCHAR(200)  NOT NULL COMMENT '중복 방지 키(예: track:{sessionId}:{trackSid})',
    `outbox_type`     VARCHAR(40)   NOT NULL COMMENT '작업 종류(START_TRACK_EGRESS)',
    `session_id`      BIGINT        NOT NULL COMMENT '대상 세션 ID',
    `payload`         VARCHAR(2000) NOT NULL COMMENT '작업 수행에 필요한 데이터(JSON, 익명 alias만 포함)',
    `status`          VARCHAR(20)   NOT NULL COMMENT 'PENDING, IN_PROGRESS, COMPLETED 또는 FAILED',
    `attempt_count`   INT           NOT NULL COMMENT '릴레이 시도 횟수(claim 시점에 증가)',
    `next_attempt_at` DATETIME(6)   NOT NULL COMMENT '이 시각 이후에만 릴레이가 소비한다(지수 백오프)',
    `last_error`      VARCHAR(500)  NULL     COMMENT '마지막 실패 사유',
    `created_at`      DATETIME(6)   NOT NULL COMMENT '행 생성 시각',
    `updated_at`      DATETIME(6)   NOT NULL COMMENT '상태 수정 시각(IN_PROGRESS lease 만료 판단에 사용)',
    CONSTRAINT `PK_RECORDING_OUTBOX` PRIMARY KEY (`id`),
    CONSTRAINT `UK_RECORDING_OUTBOX_DEDUP_KEY` UNIQUE (`dedup_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `IX_RECORDING_OUTBOX_STATUS_NEXT_ATTEMPT_AT` ON `recording_outbox` (`status`, `next_attempt_at`);
