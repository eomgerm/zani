CREATE TABLE `coaching_histories` (
    `id` BIGINT NOT NULL COMMENT 'TSID primary key',
    `session_id` BIGINT NOT NULL COMMENT 'Lecture session that owns this coaching result',
    `trigger_id` VARCHAR(64) NOT NULL COMMENT 'Idempotency key unique inside the session',
    `triggered_at` DATETIME(6) NOT NULL COMMENT 'Absolute UTC time when coaching was triggered',
    `completed_at` DATETIME(6) NOT NULL COMMENT 'Absolute UTC time when coaching processing completed',
    `denominator_count` INT NOT NULL COMMENT 'Anonymous students included in the trigger snapshot',
    `selected_tip_type` VARCHAR(50) NULL COMMENT 'Tip type selected before generation',
    `outcome_status` VARCHAR(30) NOT NULL COMMENT 'TIP_DELIVERED or TIP_UNAVAILABLE',
    `transcript_status` VARCHAR(30) NOT NULL COMMENT 'TRANSCRIBED, SKIPPED_NOT_REQUIRED, NOT_ATTEMPTED, TRANSCRIPTION_FAILED, or NO_TRANSCRIPT',
    `transcript_started_at` DATETIME(6) NULL COMMENT 'Absolute UTC start of the real-time transcript interval',
    `transcript_ended_at` DATETIME(6) NULL COMMENT 'Absolute UTC end of the real-time transcript interval',
    `topic` VARCHAR(500) NULL COMMENT 'Topic extracted from the real-time transcript without retaining the full text',
    `tip_type` VARCHAR(50) NULL COMMENT 'Delivered coaching tip type',
    `tip_title` VARCHAR(200) NULL COMMENT 'Delivered coaching tip title',
    `tip_message` TEXT NULL COMMENT 'Delivered coaching tip message',
    `unavailable_reason` VARCHAR(50) NULL COMMENT 'Reason a coaching tip was not delivered',
    `created_at` DATETIME(6) NOT NULL COMMENT 'Persistence time in UTC',
    CONSTRAINT `PK_COACHING_HISTORIES` PRIMARY KEY (`id`),
    CONSTRAINT `UK_COACHING_HISTORIES_SESSION_TRIGGER` UNIQUE (`session_id`, `trigger_id`),
    CONSTRAINT `FK_COACHING_HISTORIES_SESSION` FOREIGN KEY (`session_id`) REFERENCES `sessions` (`id`),
    CONSTRAINT `CK_COACHING_HISTORIES_OUTCOME` CHECK (
        (`outcome_status` = 'TIP_DELIVERED' AND `tip_type` IS NOT NULL AND `unavailable_reason` IS NULL)
        OR (`outcome_status` = 'TIP_UNAVAILABLE' AND `tip_type` IS NULL AND `unavailable_reason` IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `coaching_history_response_counts` (
    `id` BIGINT NOT NULL COMMENT 'TSID primary key',
    `coaching_history_id` BIGINT NOT NULL COMMENT 'Owning coaching history row',
    `response_type` VARCHAR(30) NOT NULL COMMENT 'SIGNIFICANT, CONFUSED, MISSED, NON_RESPONSE, or UNMEASURABLE',
    `response_count` INT NOT NULL COMMENT 'Exact anonymous student count',
    `created_at` DATETIME(6) NOT NULL COMMENT 'Persistence time in UTC',
    CONSTRAINT `PK_COACHING_HISTORY_RESPONSE_COUNTS` PRIMARY KEY (`id`),
    CONSTRAINT `UK_COACHING_HISTORY_RESPONSE_COUNTS_HISTORY_TYPE`
        UNIQUE (`coaching_history_id`, `response_type`),
    CONSTRAINT `FK_COACHING_HISTORY_RESPONSE_COUNTS_HISTORY`
        FOREIGN KEY (`coaching_history_id`) REFERENCES `coaching_histories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX `IX_COACHING_HISTORIES_SESSION_TRIGGERED_AT`
    ON `coaching_histories` (`session_id`, `triggered_at`);
