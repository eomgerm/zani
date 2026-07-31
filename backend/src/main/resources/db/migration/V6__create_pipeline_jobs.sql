-- 강사 메모 확정 후 시작되는 사후 처리 작업(FRD §16 NOTE-004, §17.1 처리 순서의 시작점).
--
-- 세션당 정확히 하나여야 하므로 멱등 키를 따로 두지 않고 session_id 에 UNIQUE 를 건다. 중복 확정·재시도에서
-- 같은 세션의 job 이 두 번 만들어지지 않는 근거가 이 제약이다. 확정은 되돌릴 수 없어(FRD §16) 재생성 경로도 없다.
--
-- recording_outbox 와 같은 이유로 sessions FK 를 두지 않는다: 큐 성격의 테이블이라 세션 행과 잠금으로 엮이지 않고
-- 독립적으로 소비·정리된다. 다만 작업 종류가 하나뿐이라 payload·타입 컬럼은 두지 않는다.
--
-- 재시도 횟수·백오프·실패 사유 컬럼은 재시도 정책(S15P11A105-107)이 필요해질 때 더한다.
CREATE TABLE `pipeline_jobs`
(
    `id`         BIGINT      NOT NULL COMMENT 'TSID 기본키',
    `session_id` BIGINT      NOT NULL COMMENT '사후 처리 대상 세션 ID',
    `status`     VARCHAR(30) NOT NULL COMMENT '처리 단계. QUEUED, TRANSCRIBING, ANALYZING, VALIDATING, PUBLISHED 또는 FAILED',
    `created_at` DATETIME(6) NOT NULL COMMENT '행 생성 시각(= 메모 확정 시각)',
    `updated_at` DATETIME(6) NOT NULL COMMENT '단계 변경 시각',
    CONSTRAINT `PK_PIPELINE_JOBS` PRIMARY KEY (`id`),
    CONSTRAINT `UK_PIPELINE_JOBS_SESSION` UNIQUE (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 소비자(상태 머신)는 QUEUED 를 오래된 것부터 가져간다.
CREATE INDEX `IX_PIPELINE_JOBS_STATUS_CREATED_AT` ON `pipeline_jobs` (`status`, `created_at`);
