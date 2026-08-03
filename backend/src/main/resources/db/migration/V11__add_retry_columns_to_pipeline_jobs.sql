-- 사후 처리 작업의 재시도 상태(S15P11A105-107). V6 가 "재시도 정책이 필요해질 때 더한다" 로 남겨 둔 컬럼들이다.
--
-- 재시도는 단계를 되돌리지 않는다. 실패한 단계를 그대로 두고 next_attempt_at 만 미뤄, 그 시각 이후에 같은 단계를
-- 다시 시도한다. 단계를 QUEUED 로 되돌리면 이미 끝난 앞 단계까지 다시 돌게 되고, 8시간 예산(AI-006)이 그만큼 사라진다.
--
-- recording_outbox·notification_outbox 와 같은 컬럼 구성이되 claim(선점) 컬럼은 두지 않는다. 그쪽은 여러 행을
-- 폴링해 나눠 가지는 큐라 선점이 필요하지만, 이 테이블은 세션당 한 행이고 전이가 이미 잠금 읽기로 직렬화된다.
ALTER TABLE `pipeline_jobs`
    ADD COLUMN `attempt_count`   INT         NOT NULL DEFAULT 0 COMMENT '현재 단계의 시도 횟수(백오프·재시도 상한 근거)' AFTER `status`,
    ADD COLUMN `next_attempt_at` DATETIME(6) NULL COMMENT '이 시각 이후에 현재 단계를 재시도할 수 있다. 대기 중이 아니면 NULL' AFTER `attempt_count`,
    ADD COLUMN `last_error`      VARCHAR(500) NULL COMMENT '마지막 실패 사유(재시도·최종 실패 기록)' AFTER `next_attempt_at`;
