-- Track Egress 원본을 최종 강의 MP4로 합성하는 비동기 작업 상태(S15P11A105-269).
-- 녹화 원본·세션은 보존/정리 수명이 서로 달라 FK를 두지 않고 식별자만 저장한다.
CREATE TABLE `recording_finalization_jobs`
(
    `id`                BIGINT       NOT NULL COMMENT 'TSID 기본키',
    `session_id`        BIGINT       NOT NULL COMMENT '최종 병합 대상 세션 ID',
    `status`            VARCHAR(30)  NOT NULL COMMENT 'PENDING, RUNNING, COMPLETED, FAILED',
    `attempt_count`     INT          NOT NULL DEFAULT 0 COMMENT '실제 worker 실행 횟수. Egress 정리 대기는 세지 않는다',
    `lease_token`       INT          NOT NULL DEFAULT 0 COMMENT 'RUNNING 실행권 fencing token',
    `lease_until`       DATETIME(6)  NULL COMMENT 'RUNNING 실행권 만료 시각',
    `next_attempt_at`   DATETIME(6)  NULL COMMENT '다음 실행 가능 시각',
    `last_error`        VARCHAR(500) NULL COMMENT '마지막 실패 사유. 경로·자격증명·원문은 넣지 않는다',
    `manifest_path`     VARCHAR(500) NULL COMMENT '컨테이너 내부 manifest 경로',
    `output_path`       VARCHAR(500) NULL COMMENT '컨테이너 내부 최종 MP4 경로',
    `output_size_bytes` BIGINT       NULL COMMENT '완성된 lecture.mp4 크기',
    `output_sha256`     CHAR(64)     NULL COMMENT '완성된 lecture.mp4 SHA-256',
    `started_at`        DATETIME(6)  NULL COMMENT '첫 worker 실행 시작 시각',
    `completed_at`      DATETIME(6)  NULL COMMENT '최종 MP4 확정 시각',
    `created_at`        DATETIME(6)  NOT NULL,
    `updated_at`        DATETIME(6)  NOT NULL,
    CONSTRAINT `PK_RECORDING_FINALIZATION_JOBS` PRIMARY KEY (`id`),
    CONSTRAINT `UK_RECORDING_FINALIZATION_JOBS_SESSION` UNIQUE (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX `IX_RECORDING_FINALIZATION_JOBS_DUE`
    ON `recording_finalization_jobs` (`status`, `next_attempt_at`, `lease_until`, `session_id`);
