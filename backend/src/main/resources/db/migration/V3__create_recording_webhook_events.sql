-- LiveKit webhook 이벤트의 내구성 저장 + 중복 방지(가이드 §11: 이벤트 id unique, 서명 검증·저장 후 빠른 2xx).
-- event_id UNIQUE가 같은 이벤트의 이중 처리를 막고, 처리 실패 시 RECEIVED로 남아 LiveKit 재전송에서 재처리된다.
CREATE TABLE `recording_webhook_events` (
    `id`         BIGINT        NOT NULL COMMENT 'TSID 기본키',
    `event_id`   VARCHAR(100)  NOT NULL COMMENT 'LiveKit webhook 이벤트 ID',
    `event_type` VARCHAR(60)   NOT NULL COMMENT 'track_published, egress_ended 등',
    `payload`    TEXT          NOT NULL COMMENT '수신 원문(JSON) — 재처리·감사용',
    `status`     VARCHAR(20)   NOT NULL COMMENT 'RECEIVED 또는 PROCESSED',
    `created_at` DATETIME(6)   NOT NULL COMMENT '수신 시각',
    `updated_at` DATETIME(6)   NOT NULL COMMENT '상태 수정 시각',
    CONSTRAINT `PK_RECORDING_WEBHOOK_EVENTS` PRIMARY KEY (`id`),
    CONSTRAINT `UK_RECORDING_WEBHOOK_EVENTS_EVENT_ID` UNIQUE (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
