-- 리포트가 준비된(session_reports.published_at 설정됨) 세션의 학생에게 보내는 알림 발송 outbox.
--
-- recording_outbox·pipeline_jobs 와 같은 이유로 sessions FK 를 두지 않는다: 큐 성격의 테이블이라 세션 행과
-- 잠금으로 엮이지 않고 독립적으로 소비·정리된다. 수신자당 한 행이며, dedup_key(세션·수신자·유형)에 UNIQUE 를 걸어
-- 재발견·재실행에서 같은 알림이 두 번 쌓이지 않게 한다(멱등 이메일의 근거).
CREATE TABLE `notification_outbox`
(
    `id`              BIGINT       NOT NULL COMMENT 'TSID 기본키',
    `session_id`      BIGINT       NOT NULL COMMENT '알림 대상 세션 ID',
    `member_id`       BIGINT       NOT NULL COMMENT '수신 회원 ID',
    `email`           VARCHAR(255) NOT NULL COMMENT '발견 시점 스냅샷 이메일',
    `display_name`    VARCHAR(100) COMMENT '발견 시점 스냅샷 이름',
    `type`            VARCHAR(40)  NOT NULL COMMENT '알림 유형. 현재 REPORT_READY',
    `dedup_key`       VARCHAR(200) NOT NULL COMMENT '세션·수신자·유형 유일 키(멱등 등록)',
    `status`          VARCHAR(30)  NOT NULL COMMENT '발송 상태. PENDING, IN_PROGRESS, SENT 또는 FAILED',
    `attempt_count`   INT          NOT NULL COMMENT 'consumer 선점 횟수(백오프·재시도 상한 근거)',
    `next_attempt_at` DATETIME(6)  NOT NULL COMMENT '이 시각 이후에 다음 시도 가능(백오프)',
    `last_error`      VARCHAR(500) COMMENT '마지막 발송 실패 사유(실패 기록)',
    `sent_at`         DATETIME(6)  COMMENT '발송 성공 시각',
    `created_at`      DATETIME(6)  NOT NULL COMMENT '행 생성 시각(= 알림 등록 시각)',
    `updated_at`      DATETIME(6)  NOT NULL COMMENT '상태 변경 시각',
    CONSTRAINT `PK_NOTIFICATION_OUTBOX` PRIMARY KEY (`id`),
    CONSTRAINT `UK_NOTIFICATION_OUTBOX_DEDUP` UNIQUE (`dedup_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- consumer 는 처리할 때가 된(PENDING & next_attempt_at 경과) 행을 오래된 순으로 가져간다.
CREATE INDEX `IX_NOTIFICATION_OUTBOX_STATUS_NEXT` ON `notification_outbox` (`status`, `next_attempt_at`);

-- producer 의 재발견 방지 조건(NOT EXISTS ... session_id = ? AND type = ?)을 뒷받침한다.
CREATE INDEX `IX_NOTIFICATION_OUTBOX_SESSION_TYPE` ON `notification_outbox` (`session_id`, `type`);
